#!/usr/bin/env python3
"""Formats per-module JaCoCo coverage as a GitHub-flavored Markdown summary.

Scans a directory tree for per-module JaCoCo XML reports
(build/reports/jacoco/test/jacocoTestReport.xml) and produces a Markdown
table with one row per module, sorted descending by module path.

Usage: format_coverage_summary.py <core-dir>

The output is suitable for $GITHUB_STEP_SUMMARY. The table is wrapped in
a <details> block so it defaults to collapsed.

Exit codes:
  0 - success
  1 - invalid arguments or no reports found
"""

import os
import sys
import xml.etree.ElementTree as ET


def parse_counter(element, counter_type):
    for counter in element.findall("counter"):
        if counter.get("type") == counter_type:
            missed = int(counter.get("missed"))
            covered = int(counter.get("covered"))
            total = missed + covered
            pct = (covered / total * 100) if total > 0 else 0.0
            return covered, total, pct
    return 0, 0, 0.0


def find_module_reports(core_dir):
    """Find all per-module jacocoTestReport.xml files and derive module names."""
    modules = []
    for dirpath, _, filenames in os.walk(core_dir):
        if "jacocoTestReport.xml" not in filenames:
            continue
        rel = os.path.relpath(dirpath, core_dir).replace("\\", "/")
        if "/build/reports/jacoco/test" not in f"/{rel}":
            continue
        xml_path = os.path.join(dirpath, "jacocoTestReport.xml")
        parts = rel.split("/")
        build_idx = parts.index("build") if "build" in parts else -1
        if build_idx > 0:
            module_path = ":".join(parts[:build_idx])
            modules.append((f":{module_path}", xml_path))
    return sorted(modules, key=lambda x: x[0])


def format_summary(core_dir):
    modules = find_module_reports(core_dir)
    if not modules:
        return "⚠️ No per-module coverage reports found."

    rows = []
    for module_name, xml_path in modules:
        try:
            tree = ET.parse(xml_path)
            root = tree.getroot()
            _, _, instr_pct = parse_counter(root, "INSTRUCTION")
            _, _, branch_pct = parse_counter(root, "BRANCH")
            rows.append((module_name, instr_pct, branch_pct))
        except Exception:
            rows.append((module_name, -1.0, -1.0))

    rows.sort(key=lambda r: r[1], reverse=True)

    lines = []
    lines.append("<details>")
    lines.append("<summary>Code Coverage by Module</summary>")
    lines.append("")
    lines.append("| Module | Instruction | Branch |")
    lines.append("|--------|----------:|---------:|")

    for module_name, instr_pct, branch_pct in rows:
        if instr_pct < 0:
            lines.append(f"| `{module_name}` | — | — |")
        else:
            lines.append(f"| `{module_name}` | {instr_pct:.1f}% | {branch_pct:.1f}% |")

    lines.append("")
    lines.append("</details>")
    return "\n".join(lines)


def main():
    if len(sys.argv) != 2:
        print(f"Usage: {sys.argv[0]} <core-dir>", file=sys.stderr)
        sys.exit(1)

    core_dir = sys.argv[1]
    if not os.path.isdir(core_dir):
        print(f"Not a directory: {core_dir}", file=sys.stderr)
        sys.exit(1)

    print(format_summary(core_dir))


if __name__ == "__main__":
    main()
