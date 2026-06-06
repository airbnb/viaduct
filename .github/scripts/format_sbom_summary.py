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
  - With multiple scan dirs, SBOMs are de-duplicated by file path (so passing a
    parent dir and a nested module dir doesn't double-count), and a module name
    found in more than one root is qualified with its scan root so same-named
    modules stay distinct instead of overwriting each other.
"""

import json
import os
import sys
from collections import Counter


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
    # Collect one record per SBOM, keyed by absolute path — NOT by module name.
    # De-duping by path means a file reached via overlapping roots (e.g. a parent
    # dir and a nested module dir) is summarized once; keeping records in a list
    # means same-named modules from different roots don't overwrite each other
    # (which would silently drop components and skew the total).
    records = []  # each: {module, path, scan_dir, label, rows, error}
    seen_paths = set()
    for d in scan_dirs:
        if not os.path.isdir(d):
            continue
        for module, path in find_sbom_reports(d):
            abs_path = os.path.abspath(path)
            if abs_path in seen_paths:
                continue
            seen_paths.add(abs_path)
            records.append({"module": module, "path": path, "scan_dir": d})

    if not records:
        return "⚠️ No SBOM reports found (build/reports/sbom/cyclonedx.json)."

    # If a module name maps to more than one distinct file (i.e. two scan roots),
    # qualify each colliding label with its scan root so the sections stay
    # distinct. Unique names keep their bare label, so single-directory output
    # (the CI path) is unchanged.
    name_counts = Counter(r["module"] for r in records)
    for r in records:
        r["label"] = (
            f"{r['module']} ({r['scan_dir']})"
            if name_counts[r["module"]] > 1
            else r["module"]
        )

    # Parse each SBOM; attach its rows or a parse error to the record.
    for r in records:
        try:
            components = load_components(r["path"])
            r["rows"] = sorted(
                (component_row(c) for c in components),
                key=lambda row: (row[0].lower(), row[1]),
            )
            r["error"] = None
        except (OSError, ValueError) as err:
            r["rows"] = None
            r["error"] = str(err)

    ok = sorted((r for r in records if r["error"] is None), key=lambda r: r["label"])
    bad = sorted((r for r in records if r["error"] is not None), key=lambda r: r["label"])

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
    for r in ok:
        total += len(r["rows"])
        lines.append(f"| `{r['label']}` | {len(r['rows'])} |")
    for r in bad:
        lines.append(f"| `{r['label']}` | ⚠️ unreadable |")
    lines.append(f"| **Total** | {total} |")
    lines.append("")

    # Per-publication detail.
    for r in ok:
        rows = r["rows"]
        lines.append("<details>")
        lines.append(f"<summary>{r['label']} — {len(rows)} components</summary>")
        lines.append("")
        if rows:
            lines.append(format_component_table(rows))
        else:
            lines.append("_No components on the runtime classpath._")
        lines.append("")
        lines.append("</details>")
        lines.append("")

    for r in bad:
        lines.append("<details>")
        lines.append(f"<summary>{r['label']} — ⚠️ unreadable</summary>")
        lines.append("")
        lines.append(f"Could not parse SBOM: {r['error']}")
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
