#!/usr/bin/env python3
"""
Compare Trivy CVE findings between fine-grained (per-module SBOM) and fat-JAR
binary scans, surfacing parity gaps.

A "parity gap" is a CVE present in a fine-grained scan that is missing from the
fat-JAR scan. These are the load-bearing findings: vulnerabilities in shaded
transitives that fat-JAR consumers' binary scanners would otherwise miss.

Robustness: gap detection keys only on two standardized identifiers --
``VulnerabilityID`` (a CVE id) and ``PkgIdentifier.PURL`` (a Package-URL) --
inside Trivy's versioned ``SchemaVersion`` envelope. Every other field is
display-only and parsed defensively, so a change to Trivy's output format
degrades the rendered report, not the gap result. An unrecognized
``SchemaVersion`` emits a loud (non-fatal) warning so format drift is noticed.

Usage:
    python3 compare_cve_findings.py \\
        --fine-grained <file>... \\
        --fat-jar <file>... \\
        [--severity-floor LOW|MEDIUM|HIGH|CRITICAL] \\
        [--output-format markdown|json] \\
        [--output PATH]

Exit codes:
    0 - no parity gaps at the configured severity floor
    1 - parity gaps found
    2 - argument or input error
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

STICKY_COMMENT_MARKER = "<!-- security-scan-summary -->"

_SEVERITY_RANK = {
    "UNKNOWN": 0,
    "LOW": 1,
    "MEDIUM": 2,
    "HIGH": 3,
    "CRITICAL": 4,
}

_VALID_SEVERITY_FLOORS = ("LOW", "MEDIUM", "HIGH", "CRITICAL")

# Trivy report SchemaVersion(s) this parser is validated against. An unexpected
# version is non-fatal (see unsupported_schema_warning) because gap detection
# only relies on standardized identifiers, but it is surfaced loudly.
SUPPORTED_TRIVY_SCHEMA_VERSIONS = frozenset({2})


@dataclass(frozen=True)
class Finding:
    cve_id: str
    purl: str
    severity: str
    pkg_name: str
    installed_version: str
    fixed_version: str | None
    title: str


def unsupported_schema_warning(data: dict, source: str = "") -> str | None:
    """Return a warning string if the Trivy SchemaVersion is unrecognized, else None.

    Non-fatal by design: gap detection keys only on standardized identifiers, so
    an unexpected schema version likely still parses — but we want a Trivy format
    change to be noticed rather than silently mis-parsed.
    """
    version = data.get("SchemaVersion")
    if version in SUPPORTED_TRIVY_SCHEMA_VERSIONS:
        return None
    where = f" in {source}" if source else ""
    supported = sorted(SUPPORTED_TRIVY_SCHEMA_VERSIONS)
    return (
        f"unrecognized Trivy SchemaVersion {version!r}{where}; "
        f"parser validated for {supported} — results may be incomplete "
        f"if Trivy's output format changed"
    )


def parse_trivy_json(path: Path) -> list[Finding]:
    """Parse a Trivy JSON report and return a list of Finding."""
    with path.open() as f:
        data = json.load(f)

    warning = unsupported_schema_warning(data, source=str(path))
    if warning:
        print(f"warning: {warning}", file=sys.stderr)

    findings: list[Finding] = []
    for result in data.get("Results") or []:
        for vuln in result.get("Vulnerabilities") or []:
            cve_id = vuln.get("VulnerabilityID", "")
            if not cve_id:
                continue
            pkg_identifier = vuln.get("PkgIdentifier") or {}
            findings.append(
                Finding(
                    cve_id=cve_id,
                    purl=pkg_identifier.get("PURL", "") or "",
                    severity=(vuln.get("Severity") or "UNKNOWN").upper(),
                    pkg_name=vuln.get("PkgName", "") or "",
                    installed_version=vuln.get("InstalledVersion", "") or "",
                    fixed_version=vuln.get("FixedVersion") or None,
                    title=vuln.get("Title", "") or "",
                )
            )
    return findings


def filter_by_severity(findings: Iterable[Finding], floor: str) -> list[Finding]:
    """Return findings whose severity rank is >= the floor's rank."""
    floor_rank = _SEVERITY_RANK[floor.upper()]
    return [f for f in findings if _SEVERITY_RANK.get(f.severity, 0) >= floor_rank]


def compare(
    fine: Iterable[Finding],
    fat: Iterable[Finding],
) -> tuple[set[Finding], set[Finding], set[Finding]]:
    """Three-way set partition keyed on (cve_id, purl).

    Two findings with the same cve_id and purl are considered the same finding,
    even if other metadata (title, fixed_version, severity) differs between
    scans. The fine-grained side's metadata wins for entries in `common`.

    Returns (only_in_fine, only_in_fat, common).
    """
    fine_map = {(f.cve_id, f.purl): f for f in fine}
    fat_map = {(f.cve_id, f.purl): f for f in fat}
    fine_keys = set(fine_map)
    fat_keys = set(fat_map)
    only_in_fine = {fine_map[k] for k in fine_keys - fat_keys}
    only_in_fat = {fat_map[k] for k in fat_keys - fine_keys}
    common = {fine_map[k] for k in fine_keys & fat_keys}
    return only_in_fine, only_in_fat, common


def _format_table(findings: Iterable[Finding]) -> str:
    rows = sorted(findings, key=lambda f: (f.severity, f.cve_id, f.purl))
    if not rows:
        return "_(none)_"
    lines = [
        "| CVE | Severity | Package | Installed | Fixed | Title |",
        "| --- | --- | --- | --- | --- | --- |",
    ]
    for f in rows:
        fixed = f.fixed_version or "_unfixed_"
        title = (f.title or "").replace("|", "\\|")
        lines.append(
            f"| {f.cve_id} | {f.severity} | {f.pkg_name} | "
            f"{f.installed_version} | {fixed} | {title} |"
        )
    return "\n".join(lines)


def render_markdown(
    only_in_fine: Iterable[Finding],
    only_in_fat: Iterable[Finding],
    common: Iterable[Finding],
    severity_floor: str,
) -> str:
    only_in_fine_list = list(only_in_fine)
    only_in_fat_list = list(only_in_fat)
    common_list = list(common)

    lines = [
        STICKY_COMMENT_MARKER,
        "## Security scan: CVE parity report",
        "",
        f"**Severity floor:** `{severity_floor.upper()}+`",
        "",
        f"- **Parity gaps:** {len(only_in_fine_list)}",
        f"- **Common findings:** {len(common_list)}",
        f"- **Fat-JAR-only findings:** {len(only_in_fat_list)}",
        "",
        "### Parity gaps",
        "",
        "_CVEs visible in fine-grained scans but missing from fat-JAR scans._",
        "",
        _format_table(only_in_fine_list),
        "",
        "<details><summary><strong>Common findings</strong></summary>",
        "",
        _format_table(common_list),
        "",
        "</details>",
        "",
        "<details><summary><strong>Fat-JAR-only findings</strong></summary>",
        "",
        _format_table(only_in_fat_list),
        "",
        "</details>",
    ]
    return "\n".join(lines)


def render_json(
    only_in_fine: Iterable[Finding],
    only_in_fat: Iterable[Finding],
    common: Iterable[Finding],
    severity_floor: str,
) -> str:
    payload = {
        "severity_floor": severity_floor.upper(),
        "parity_gaps": [asdict(f) for f in only_in_fine],
        "common": [asdict(f) for f in common],
        "fat_jar_only": [asdict(f) for f in only_in_fat],
    }
    return json.dumps(payload, indent=2, sort_keys=True)


def _load_findings(paths: list[str]) -> list[Finding]:
    findings: list[Finding] = []
    for raw in paths:
        path = Path(raw)
        if not path.is_file():
            raise FileNotFoundError(f"Input file not found: {raw}")
        findings.extend(parse_trivy_json(path))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Compare Trivy CVE findings between fine-grained and fat-JAR scans."
        ),
    )
    parser.add_argument(
        "--fine-grained",
        nargs="+",
        required=True,
        help="One or more Trivy JSON reports from fine-grained (SBOM) scans.",
    )
    parser.add_argument(
        "--fat-jar",
        nargs="+",
        required=True,
        help="One or more Trivy JSON reports from fat-JAR binary scans.",
    )
    parser.add_argument(
        "--severity-floor",
        default="HIGH",
        choices=_VALID_SEVERITY_FLOORS,
        help="Lowest severity to include (default: HIGH).",
    )
    parser.add_argument(
        "--output-format",
        default="markdown",
        choices=("markdown", "json"),
        help="Output format (default: markdown).",
    )
    parser.add_argument(
        "--output",
        default=None,
        help="Write to PATH instead of stdout.",
    )
    args = parser.parse_args(argv)

    try:
        fine = _load_findings(args.fine_grained)
        fat = _load_findings(args.fat_jar)
    except FileNotFoundError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(f"error: invalid JSON: {exc}", file=sys.stderr)
        return 2

    fine = filter_by_severity(fine, args.severity_floor)
    fat = filter_by_severity(fat, args.severity_floor)

    only_in_fine, only_in_fat, common = compare(fine, fat)

    if args.output_format == "markdown":
        rendered = render_markdown(
            only_in_fine, only_in_fat, common, args.severity_floor
        )
    else:
        rendered = render_json(
            only_in_fine, only_in_fat, common, args.severity_floor
        )

    if args.output:
        Path(args.output).write_text(rendered + "\n")
    else:
        print(rendered)

    return 1 if only_in_fine else 0


if __name__ == "__main__":
    sys.exit(main())
