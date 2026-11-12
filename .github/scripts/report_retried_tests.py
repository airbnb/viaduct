#!/usr/bin/env python3
"""Lists the tests that failed and then passed on a test-retry attempt, as Markdown.

Usage: report_retried_tests.py <dir> [<run-started-at>]

Scans <dir> for JUnit XML under build/test-results and always exits 0 on valid usage.
Suites that started before <run-started-at> (ISO 8601) came from the build cache and are skipped.
"""

import html
import os
import re
import sys
from dataclasses import dataclass, field
from datetime import datetime
from defusedxml import DefusedXmlException, ElementTree as ET

SEED = re.compile(r"Repeat this test by using seed (-?\d+)")
MAX_ROWS = 50
# maxRetries in build-logic/src/main/kotlin/conventions/test-retry.gradle.kts, plus the first attempt.
MAX_ATTEMPTS = 3


@dataclass
class Report:
    rows: list = field(default_factory=list)
    scanned: int = 0
    cached: int = 0
    retries_off: list = field(default_factory=list)


def find_tasks(scan_dir):
    for dirpath, _, filenames in os.walk(scan_dir):
        rel = os.path.relpath(dirpath, scan_dir).replace("\\", "/")
        reports = [os.path.join(dirpath, name) for name in sorted(filenames) if name.endswith(".xml")]
        if reports and "/build/test-results/" in f"/{rel}/":
            yield rel, reports


def outcome(case):
    if case.find("skipped") is not None:
        return "skipped", None
    for tag in ("failure", "error"):
        element = case.find(tag)
        if element is not None:
            return "failed", (element.get("message") or "") + (element.text or "")
    return "passed", None


def ran_before(suite, since):
    try:
        return since is not None and datetime.fromisoformat(suite.get("timestamp", "")) < since
    except (ValueError, TypeError):
        return False


def retried_tests(scan_dir, since=None):
    report = Report()
    for rel, paths in find_tasks(scan_dir):
        directory = f"/{rel}".split("/build/")[0].strip("/") or "."
        attempts = {}
        for path in paths:
            try:
                suite = ET.parse(path).getroot()
            except (ET.ParseError, DefusedXmlException, OSError):
                continue
            if ran_before(suite, since):
                report.cached += 1
                continue
            report.scanned += 1
            for case in suite.iter("testcase"):
                key = (case.get("classname", ""), case.get("name", ""))
                attempts.setdefault(key, []).append(outcome(case))
        groups = [(key, [text for status, text in outcomes if status == "failed"], {status for status, _ in outcomes})
                  for key, outcomes in attempts.items()]
        # test-retry reruns every failed test unless maxFailures turned retries off for the whole task.
        if any(statuses == {"failed"} and len(failures) < MAX_ATTEMPTS for _, failures, statuses in groups):
            report.retries_off.append(directory)
            continue
        for (classname, name), failures, statuses in groups:
            # Same-name parameterized invocations share a key, so a group with an always-failing one is skipped.
            if failures and "passed" in statuses and len(failures) < MAX_ATTEMPTS:
                seed = next((m.group(1) for m in map(SEED.search, failures) if m), "")
                report.rows.append((directory, f"{classname} > {name}", len(failures), seed))
    report.rows.sort()
    return report


def cell(value):
    return html.escape(str(value), quote=False).replace("|", "\\|").replace("\n", " ")


def format_report(report):
    lines = ["### Tests that passed only after a retry", ""]
    if not report.scanned and not report.cached:
        return "\n".join(lines + ["No JUnit XML found.", ""])
    if not report.rows:
        lines += ["No test passed after a retry."]
    else:
        lines += [
            f"{len(report.rows)} test(s) failed at least once and then passed.",
            "",
            "| Directory | Test | Failed attempts | Seed |",
            "|---|---|---|---|",
        ]
        lines += [f"| {' | '.join(cell(v) for v in row)} |" for row in report.rows[:MAX_ROWS]]
        if len(report.rows) > MAX_ROWS:
            lines += ["", f"{len(report.rows) - MAX_ROWS} more not shown; see the JUnit XML artifact."]
    for directory in sorted(set(report.retries_off)):
        lines += ["", f"Not checked: {cell(directory)} has a failed test that was never retried, so retries were off."]
    if report.cached:
        lines += ["", f"Not checked: {report.cached} suite(s) restored from the build cache."]
    return "\n".join(lines + [""])


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 1
    since = datetime.fromisoformat(argv[2]) if len(argv) == 3 and argv[2] else None
    print(format_report(retried_tests(argv[1], since)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
