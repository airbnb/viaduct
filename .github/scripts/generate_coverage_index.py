#!/usr/bin/env python3
"""Assembles a hierarchical HTML coverage report from per-module JaCoCo outputs.

For each sub-project directory under <core-dir> that has a per-module JaCoCo HTML
report at build/reports/jacoco/test/html/, copies that directory tree into
<out-dir>/<module-path>/ and then writes an index.html at <out-dir>/index.html
with a color-coded table of instruction and branch coverage percentages.

Usage:
    generate_coverage_index.py <core-dir> <out-dir>

    <core-dir>   Root of the core included build (e.g. "core")
    <out-dir>    Destination directory for assembled report (created if absent)

Exit codes:
    0 - success (index written, ≥1 module included)
    1 - usage error or no reports found
"""

import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET


def parse_counter(root, counter_type):
    for counter in root.findall("counter"):
        if counter.get("type") == counter_type:
            missed = int(counter.get("missed", "0"))
            covered = int(counter.get("covered", "0"))
            total = missed + covered
            pct = (covered / total * 100) if total > 0 else 0.0
            return covered, total, pct
    return 0, 0, 0.0


def find_module_reports(core_dir):
    """Return list of (module_path, xml_path, html_dir) for every per-module report.

    Handles two layouts produced by Gradle:
    - Standard:  <module>/build/reports/jacoco/test/
    - Viaduct:   build/<module>/reports/jacoco/test/
      (when settings.gradle.kts redirects buildDirectory to a shared dist root)
    """
    results = []
    for dirpath, dirnames, filenames in os.walk(core_dir):
        if "jacocoTestReport.xml" not in filenames:
            continue
        rel = os.path.relpath(dirpath, core_dir).replace("\\", "/")
        parts = rel.split("/")
        if "build" not in parts:
            continue
        build_idx = parts.index("build")
        after_build = parts[build_idx + 1:]

        if after_build[:3] == ["reports", "jacoco", "test"]:
            # Standard layout: <module>/build/reports/jacoco/test
            module_path = "/".join(parts[:build_idx]) or "(root)"
        elif "reports" in after_build:
            # Viaduct layout: build/<module>/reports/jacoco/test
            reports_idx = after_build.index("reports")
            if after_build[reports_idx:reports_idx + 3] != ["reports", "jacoco", "test"]:
                continue
            module_path = "/".join(after_build[:reports_idx])
        else:
            continue

        xml_path = os.path.join(dirpath, "jacocoTestReport.xml")
        html_dir = os.path.join(dirpath, "html")
        results.append((module_path, xml_path, html_dir if os.path.isdir(html_dir) else None))
    return sorted(results, key=lambda t: t[0])


def coverage_bar(covered_pct):
    """Return an HTML coverage bar (red=missed, green=covered) + percentage label."""
    if covered_pct < 0:
        return "<td>—</td>"
    missed_pct = 100.0 - covered_pct
    bar = (
        f'<div class="bar">'
        f'<div class="missed" style="width:{missed_pct:.1f}%"></div>'
        f'<div class="covered" style="width:{covered_pct:.1f}%"></div>'
        f'</div>'
        f'<span class="pct">{covered_pct:.0f}% coverage</span>'
    )
    return f'<td class="coverage-cell">{bar}</td>'


def build_index_html(rows):
    """rows: list of (module_path, instr_pct, branch_pct, has_html)"""
    table_rows = []
    for module_path, instr_pct, branch_pct, has_html in rows:
        display = module_path if module_path else "(root)"
        if has_html:
            toggle = (
                f'<button class="toggle" onclick="toggleFrame(this)" '
                f'data-src="{module_path}/index.html">&#x2795;</button>'
            )
            name_cell = f'<td>{toggle} {display}</td>'
            frame_row = (
                f'<tr class="frame-row" style="display:none">'
                f'<td colspan="3" class="frame-cell">'
                f'<iframe src="" style="width:100%;height:600px;border:none;"></iframe>'
                f'</td></tr>'
            )
        else:
            name_cell = f'<td>{display}</td>'
            frame_row = ""

        instr_cell = coverage_bar(instr_pct)
        branch_cell = coverage_bar(branch_pct)
        table_rows.append(
            f'  <tr class="data-row">{name_cell}{instr_cell}{branch_cell}</tr>'
            + (f'\n  {frame_row}' if frame_row else "")
        )

    rows_html = "\n".join(table_rows)
    return f"""\
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>Viaduct OSS — Coverage Report</title>
  <style>
    body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
           margin: 2rem; background: #f6f8fa; color: #24292f; }}
    h1 {{ font-size: 1.5rem; margin-bottom: 1rem; }}
    table {{ border-collapse: collapse; width: 100%; background: #fff;
             box-shadow: 0 1px 3px rgba(0,0,0,.12); border-radius: 6px; overflow: hidden; }}
    th {{ background: #0d1117; color: #f0f6ff; padding: .6rem 1rem; text-align: left; }}
    td {{ padding: .4rem 1rem; border-top: 1px solid #d0d7de; vertical-align: middle; }}
    tr.data-row:hover td {{ background: #f0f6ff; }}
    .coverage-cell {{ width: 220px; }}
    .bar {{ display: flex; height: 10px; width: 120px; border-radius: 2px;
            overflow: hidden; background: #d0d7de; margin-bottom: 2px; }}
    .missed {{ background: #cf222e; height: 100%; }}
    .covered {{ background: #2da44e; height: 100%; }}
    .pct {{ font-size: .8rem; color: #57606a; font-variant-numeric: tabular-nums; }}
    .toggle {{ background: none; border: none; cursor: pointer; font-size: .9rem;
               padding: 0 4px; color: #0969da; }}
    .frame-cell {{ padding: 0; }}
    iframe {{ display: block; }}
  </style>
</head>
<body>
  <h1>Viaduct OSS — JaCoCo Coverage Report</h1>
  <table>
    <thead>
      <tr><th>Module</th><th>Instructions</th><th>Branches</th></tr>
    </thead>
    <tbody>
{rows_html}
    </tbody>
  </table>
  <script>
    function toggleFrame(btn) {{
      var frameRow = btn.closest('tr').nextElementSibling;
      if (!frameRow || !frameRow.classList.contains('frame-row')) return;
      var iframe = frameRow.querySelector('iframe');
      if (frameRow.style.display === 'none') {{
        if (!iframe.src || iframe.src === window.location.href) {{
          iframe.src = btn.dataset.src;
        }}
        frameRow.style.display = '';
        btn.innerHTML = '&#x2796;';
      }} else {{
        frameRow.style.display = 'none';
        btn.innerHTML = '&#x2795;';
      }}
    }}
  </script>
</body>
</html>
"""


def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <core-dir> <out-dir>", file=sys.stderr)
        sys.exit(1)

    core_dir, out_dir = sys.argv[1], sys.argv[2]
    if not os.path.isdir(core_dir):
        print(f"Not a directory: {core_dir}", file=sys.stderr)
        sys.exit(1)

    modules = find_module_reports(core_dir)
    if not modules:
        print("No JaCoCo reports found.", file=sys.stderr)
        sys.exit(1)

    os.makedirs(out_dir, exist_ok=True)

    rows = []
    for module_path, xml_path, html_dir in modules:
        try:
            tree = ET.parse(xml_path)
            root = tree.getroot()
            _, _, instr_pct = parse_counter(root, "INSTRUCTION")
            _, _, branch_pct = parse_counter(root, "BRANCH")
        except Exception:
            instr_pct, branch_pct = -1.0, -1.0

        has_html = False
        if html_dir:
            dest = os.path.join(out_dir, module_path)
            if os.path.exists(dest):
                shutil.rmtree(dest)
            shutil.copytree(html_dir, dest)
            has_html = True

        rows.append((module_path, instr_pct, branch_pct, has_html))

    index_path = os.path.join(out_dir, "index.html")
    with open(index_path, "w", encoding="utf-8") as f:
        f.write(build_index_html(rows))

    print(f"Coverage index written to {index_path} ({len(rows)} modules)")


if __name__ == "__main__":
    main()
