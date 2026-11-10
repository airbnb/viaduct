#!/usr/bin/env python3
"""Lists the tests that failed and then passed on a test-retry attempt, as Markdown.

Usage: report_retried_tests.py <dir>

Scans <dir> for JUnit XML under build/test-results and always exits 0 on valid usage.
"""

import html
import os
import re
import sys
import xml.etree.ElementTree as ET

SEED = re.compile(r"Repeat this test by using seed (-?\d+)")
MAX_ROWS = 50
# maxRetries 2 in build-logic/src/main/kotlin/conventions/test-retry.gradle.kts, plus the first attempt.
MAX_ATTEMPTS = 3


def find_reports(scan_dir):
    for dirpath, _, filenames in os.walk(scan_dir):
        rel = os.path.relpath(dirpath, scan_dir).replace("\\", "/")
        if "/build/test-results/" not in f"/{rel}/":
            continue
        for name in sorted(filenames):
            if name.endswith(".xml"):
                yield rel, os.path.join(dirpath, name)


def failure_text(case):
    for tag in ("failure", "error"):
        element = case.find(tag)
        if element is not None:
            return (element.get("message") or "") + (element.text or "")
    return None


def retried_tests(scan_dir):
    rows, scanned = [], 0
    for rel, path in find_reports(scan_dir):
        try:
            suite = ET.parse(path).getroot()
        except (ET.ParseError, OSError):
            continue
        scanned += 1
        directory = f"/{rel}".split("/build/")[0].strip("/") or "."
        attempts = {}
        for case in suite.iter("testcase"):
            if case.find("skipped") is None:
                key = (case.get("classname", ""), case.get("name", ""))
                attempts.setdefault(key, []).append(failure_text(case))
        for (classname, name), outcomes in attempts.items():
            failures = [text for text in outcomes if text is not None]
            # Same-name parameterized invocations share a key, so a group with an always-failing one is skipped.
            if failures and None in outcomes and len(failures) < MAX_ATTEMPTS:
                seed = next((m.group(1) for m in map(SEED.search, failures) if m), "")
                rows.append((directory, f"{classname} > {name}", len(failures), seed))
    return sorted(rows), scanned


def cell(value):
    return html.escape(str(value), quote=False).replace("|", "\\|").replace("\n", " ")


def format_report(rows, scanned):
    lines = ["### Tests that passed only after a retry", ""]
    if not scanned:
        return "\n".join(lines + ["No JUnit XML found.", ""])
    if not rows:
        return "\n".join(lines + ["No test needed a retry.", ""])
    lines += [
        f"{len(rows)} test(s) failed at least once and then passed.",
        "",
        "| Directory | Test | Failed attempts | Seed |",
        "|---|---|---|---|",
    ]
    lines += [f"| {' | '.join(cell(v) for v in row)} |" for row in rows[:MAX_ROWS]]
    if len(rows) > MAX_ROWS:
        lines += ["", f"{len(rows) - MAX_ROWS} more not shown; see the JUnit XML artifact."]
    return "\n".join(lines + [""])


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 1
    print(format_report(*retried_tests(argv[1])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
