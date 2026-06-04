#!/usr/bin/env python3
"""Formats per-publication CycloneDX SBOMs as a GitHub-flavored Markdown summary.

Scans one or more directory trees for per-module CycloneDX SBOMs
(build/reports/sbom/cyclonedx.json) and renders, for each publication, the list
of dependency components on its runtime classpath — i.e. what gets bundled into
that publication's fat JAR.

Usage: format_sbom_summary.py <dir> [<dir> ...]

Output has two sections:
  1. An overview table (not collapsed): publication -> component count, + total.
  2. A collapsed <details> section per publication with a
     Dependency / Version / License table, sorted by dependency.

Exit codes:
  0 - success (INCLUDING the "no SBOMs found" case: this feeds an advisory job,
      so an empty/short scan should render a notice, not fail the step)
  2 - usage error (no directory arguments)

Notes:
  - The SBOM is generated over `runtimeClasspath`, which is the dependency graph
    a publication bundles. It is close to, but not byte-identical with, the
    shaded fat-JAR contents (the shadow step resolves compileClasspath and
    applies excludes). The caption reflects this.
  - A component's license can be encoded three ways in CycloneDX: a license
    object with `id` (SPDX), a license object with `name` (non-SPDX), or an SPDX
    `expression`. All three are handled; the bulky embedded license `text` is
    ignored.
"""

import json
import os
import sys


def find_sbom_reports(scan_dir):
    """Find all per-module cyclonedx.json files under scan_dir.

    Returns a list of (module_name, json_path). The module name is the path
    segment(s) between scan_dir and the `build/reports/sbom` directory, joined
    with ':' (e.g. publications/api/build/reports/sbom -> "api").
    """
    reports = []
    for dirpath, _, filenames in os.walk(scan_dir):
        if "cyclonedx.json" not in filenames:
            continue
        rel = os.path.relpath(dirpath, scan_dir).replace("\\", "/")
        if not rel.endswith("build/reports/sbom"):
            continue
        parts = rel.split("/")
        build_idx = parts.index("build")
        if build_idx > 0:
            module = ":".join(parts[:build_idx])
        else:
            # The scan dir is itself the module root (rel == build/reports/sbom).
            module = os.path.basename(os.path.normpath(scan_dir))
        reports.append((module, os.path.join(dirpath, "cyclonedx.json")))
    return reports


def extract_license(component):
    """Return a human-readable license string for a CycloneDX component.

    Handles the license-object (`id` / `name`) and `expression` encodings,
    de-duplicates while preserving order, and falls back to an em dash.
    """
    names = []
    for entry in component.get("licenses") or []:
        lic = entry.get("license")
        if isinstance(lic, dict):
            value = lic.get("id") or lic.get("name")
            if value:
                names.append(value)
        elif entry.get("expression"):
            names.append(entry["expression"])
    deduped = list(dict.fromkeys(names))
    return ", ".join(deduped) if deduped else "—"


def component_row(component):
    """Map a CycloneDX component to a (dependency, version, license) row."""
    group = (component.get("group") or "").strip()
    name = (component.get("name") or "?").strip()
    dependency = f"{group}:{name}" if group else name
    version = (component.get("version") or "—").strip() or "—"
    return (dependency, version, extract_license(component))


def load_components(sbom_path):
    """Parse a CycloneDX JSON file and return its `components` list (may raise)."""
    with open(sbom_path, encoding="utf-8") as f:
        data = json.load(f)
    return data.get("components") or []


def format_component_table(rows):
    lines = ["| Dependency | Version | License |", "|---|---|---|"]
    for dependency, version, license_str in rows:
        lines.append(f"| `{dependency}` | {version} | {license_str} |")
    return "\n".join(lines)


def format_summary(scan_dirs):
    reports = []
    for d in scan_dirs:
        if os.path.isdir(d):
            reports.extend(find_sbom_reports(d))

    if not reports:
        return "⚠️ No SBOM reports found (build/reports/sbom/cyclonedx.json)."

    # Parse each module once; remember failures so the overview can flag them.
    parsed = {}  # module -> sorted rows
    failed = {}  # module -> error message
    for module, path in sorted(reports):
        try:
            components = load_components(path)
            rows = sorted(
                (component_row(c) for c in components),
                key=lambda r: (r[0].lower(), r[1]),
            )
            parsed[module] = rows
        except (OSError, ValueError) as err:
            failed[module] = str(err)

    lines = ["### SBOM: fat JAR contents", ""]
    lines.append(
        "_Dependency components on each publication's runtime classpath "
        "(CycloneDX SBOM). Close to, but not byte-identical with, the shaded "
        "fat-JAR contents._"
    )
    lines.append("")

    # Overview table.
    lines.append("| Publication | Components |")
    lines.append("|---|---:|")
    total = 0
    for module in sorted(parsed):
        count = len(parsed[module])
        total += count
        lines.append(f"| `{module}` | {count} |")
    for module in sorted(failed):
        lines.append(f"| `{module}` | ⚠️ unreadable |")
    lines.append(f"| **Total** | {total} |")
    lines.append("")

    # Per-publication detail.
    for module in sorted(parsed):
        rows = parsed[module]
        lines.append("<details>")
        lines.append(f"<summary>{module} — {len(rows)} components</summary>")
        lines.append("")
        if rows:
            lines.append(format_component_table(rows))
        else:
            lines.append("_No components on the runtime classpath._")
        lines.append("")
        lines.append("</details>")
        lines.append("")

    for module in sorted(failed):
        lines.append("<details>")
        lines.append(f"<summary>{module} — ⚠️ unreadable</summary>")
        lines.append("")
        lines.append(f"Could not parse SBOM: {failed[module]}")
        lines.append("")
        lines.append("</details>")
        lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <dir> [<dir> ...]", file=sys.stderr)
        sys.exit(2)
    print(format_summary(sys.argv[1:]))


if __name__ == "__main__":
    main()
