#!/usr/bin/env python3
"""Formats per-module JaCoCo coverage as a GitHub-flavored Markdown summary.

Scans one or more directory trees for per-module JaCoCo XML reports
(build/reports/jacoco/test/jacocoTestReport.xml) and produces Markdown
tables.

Usage: format_coverage_summary.py <dir> [<dir> ...]

Output has two sections:
  1. A top-level table (not collapsed) with key modules:
     :core:{engine,tenant,service}:{api,runtime}
  2. A collapsed <details> section with ALL modules sorted alphabetically.

Exit codes:
  0 - success
  1 - invalid arguments or no reports found
"""

import os
import re
import sys
import xml.etree.ElementTree as ET


KEY_MODULE_PATTERN = re.compile(
    r"^:core:(engine|tenant|service):(api|runtime)$"
)


def parse_counter(element, counter_type):
    for counter in element.findall("counter"):
        if counter.get("type") == counter_type:
            missed = int(counter.get("missed"))
            covered = int(counter.get("covered"))
            total = missed + covered
            pct = (covered / total * 100) if total > 0 else 0.0
            return covered, total, pct
    return 0, 0, 0.0


def find_module_reports(scan_dir):
    """Find all per-module jacocoTestReport.xml files under scan_dir."""
    modules = []
    dir_name = os.path.basename(os.path.normpath(scan_dir))
    for dirpath, _, filenames in os.walk(scan_dir):
        if "jacocoTestReport.xml" not in filenames:
            continue
        rel = os.path.relpath(dirpath, scan_dir).replace("\\", "/")
        if "/build/reports/jacoco/test" not in f"/{rel}":
            continue
        xml_path = os.path.join(dirpath, "jacocoTestReport.xml")
        parts = rel.split("/")
        build_idx = parts.index("build") if "build" in parts else -1
        if build_idx > 0:
            module_path = ":".join(parts[:build_idx])
            modules.append((f":{dir_name}:{module_path}", xml_path))
    return modules


def parse_row(module_name, xml_path):
    try:
        tree = ET.parse(xml_path)
        root = tree.getroot()
        _, _, instr_pct = parse_counter(root, "INSTRUCTION")
        _, _, branch_pct = parse_counter(root, "BRANCH")
        return (module_name, instr_pct, branch_pct)
    except Exception:
        return (module_name, -1.0, -1.0)


def format_table(rows):
    lines = []
    lines.append("| Module | Instruction | Branch |")
    lines.append("|--------|----------:|--------:|")
    for module_name, instr_pct, branch_pct in rows:
        if instr_pct < 0:
            lines.append(f"| `{module_name}` | — | — |")
        else:
            lines.append(
                f"| `{module_name}` | {instr_pct:.1f}% | {branch_pct:.1f}% |"
            )
    return "\n".join(lines)


def format_summary(scan_dirs):
    all_modules = []
    for d in scan_dirs:
        all_modules.extend(find_module_reports(d))

    if not all_modules:
        return "⚠️ No per-module coverage reports found."

    rows = [parse_row(name, path) for name, path in all_modules]

    key_rows = sorted(
        [r for r in rows if KEY_MODULE_PATTERN.match(r[0])],
        key=lambda r: r[0],
    )
    all_rows = sorted(rows, key=lambda r: r[0])

    lines = []
    if key_rows:
        lines.append("### Key Module Coverage")
        lines.append("")
        lines.append(format_table(key_rows))
        lines.append("")

    lines.append("<details>")
    lines.append("<summary>All Module Coverage</summary>")
    lines.append("")
    lines.append(format_table(all_rows))
    lines.append("")
    lines.append("</details>")
    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <dir> [<dir> ...]", file=sys.stderr)
        sys.exit(1)

    scan_dirs = []
    for d in sys.argv[1:]:
        if not os.path.isdir(d):
            print(f"Not a directory: {d}", file=sys.stderr)
            sys.exit(1)
        scan_dirs.append(d)

    print(format_summary(scan_dirs))


if __name__ == "__main__":
    main()
