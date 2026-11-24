#!/usr/bin/env python3
"""
Compare Trivy CVE findings between each publication's SBOM scan and the Trivy
scans of its published jar, surfacing parity gaps per jar.

Each publication is compared together with the lower jars its POM depends on,
because that is what a consumer resolves. A "parity gap" is a CVE the SBOM scans
of that stack list but no jar scan of the stack finds: a vulnerability in a
bundled dependency that consumers' scanners cannot see.

An SBOM can list modules its jar does not bundle (``api``'s SBOM lists
``runtimeClasspath``, but the jar bundles ``compileClasspath``). Each publication's
``bundled-modules.txt`` lists the modules with at least one class in its jar. SBOM
findings for a Maven module that no jar in the stack lists are reported as not
bundled instead of as gaps.

Robustness: gap detection keys only on two standardized identifiers --
``VulnerabilityID`` (a CVE id) and ``PkgIdentifier.PURL`` (a Package-URL,
without its qualifiers) -- inside Trivy's versioned ``SchemaVersion``
envelope. Every other field is display-only and parsed
defensively, so a change to Trivy's output format
degrades the rendered report, not the gap result. An unrecognized
``SchemaVersion`` emits a loud (non-fatal) warning so format drift is noticed.

Usage:
    python3 compare_cve_findings.py \\
        --publications-dir publications \\
        --jar-scans-dir <dir with one <publication>.json per jar> \\
        [--severity-floor LOW|MEDIUM|HIGH|CRITICAL] \\
        [--output-format markdown|json] \\
        [--output PATH]

Exit codes:
    0 - every jar was compared and none has parity gaps at the severity floor
    1 - parity gaps found, or a jar could not be compared
    2 - argument error, or no publications found
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from defusedxml import DefusedXmlException, ElementTree

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

SBOM_SCAN_REPORT = Path("build/reports/trivy/cve-report.json")
BUNDLED_MODULES = Path("build/reports/bundled-modules.txt")
GENERATED_POM = Path("build/publications/maven/pom-default.xml")
_POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


@dataclass(frozen=True)
class Finding:
    cve_id: str
    purl: str
    severity: str
    pkg_name: str
    installed_version: str
    fixed_version: str | None
    title: str


@dataclass(frozen=True)
class JarParity:
    name: str
    lower_jars: tuple[str, ...]
    missing_inputs: tuple[str, ...] = ()
    gaps: frozenset[Finding] = frozenset()
    not_bundled: frozenset[Finding] = frozenset()
    common: frozenset[Finding] = frozenset()
    fat_jar_only: frozenset[Finding] = frozenset()
    jar_packages: frozenset[tuple[str, str]] = frozenset()


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


class UnreadableReport(Exception):
    pass


def read_trivy_report(path: Path) -> dict:
    """Load a Trivy JSON report, raising UnreadableReport if it is not a JSON object."""
    try:
        with path.open() as f:
            data = json.load(f)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise UnreadableReport(f"{path}: {exc}") from exc
    if not isinstance(data, dict):
        raise UnreadableReport(f"{path}: expected a JSON object, got {type(data).__name__}")
    warning = unsupported_schema_warning(data, source=str(path))
    if warning:
        print(f"warning: {warning}", file=sys.stderr)
    return data


def findings_in(data: dict) -> list[Finding]:
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


def packages_in(data: dict) -> set[tuple[str, str]]:
    """Return the (name, version) of every package a Trivy report detected."""
    return {
        (package.get("Name", ""), package.get("Version", ""))
        for result in data.get("Results") or []
        for package in result.get("Packages") or []
    }


def parse_trivy_json(path: Path) -> list[Finding]:
    """Parse a Trivy JSON report and return a list of Finding."""
    return findings_in(read_trivy_report(path))


def filter_by_severity(findings: Iterable[Finding], floor: str) -> list[Finding]:
    """Return findings whose severity rank is >= the floor's rank."""
    floor_rank = _SEVERITY_RANK[floor.upper()]
    return [f for f in findings if _SEVERITY_RANK.get(f.severity, 0) >= floor_rank]


def _purl_without_qualifiers(purl: str) -> str:
    return purl.split("?", 1)[0]


def compare(
    fine: Iterable[Finding],
    fat: Iterable[Finding],
) -> tuple[set[Finding], set[Finding], set[Finding]]:
    """Three-way set partition keyed on (cve_id, purl without qualifiers).

    Two findings with the same cve_id and purl without qualifiers are the
    same finding, even if other metadata (title, fixed_version, severity)
    differs between scans. The fine-grained side's metadata wins for entries
    in `common`.

    Returns (only_in_fine, only_in_fat, common).
    """
    fine_map = {(f.cve_id, _purl_without_qualifiers(f.purl)): f for f in fine}
    fat_map = {(f.cve_id, _purl_without_qualifiers(f.purl)): f for f in fat}
    fine_keys = set(fine_map)
    fat_keys = set(fat_map)
    only_in_fine = {fine_map[k] for k in fine_keys - fat_keys}
    only_in_fat = {fat_map[k] for k in fat_keys - fine_keys}
    common = {fine_map[k] for k in fine_keys & fat_keys}
    return only_in_fine, only_in_fat, common


def _finding_key(finding: Finding) -> tuple[str, str]:
    return finding.cve_id, _purl_without_qualifiers(finding.purl)


def _maven_module(finding: Finding) -> str | None:
    purl = _purl_without_qualifiers(finding.purl)
    if not purl.startswith("pkg:maven/"):
        return None
    group, _, rest = purl.removeprefix("pkg:maven/").partition("/")
    return f"{group}:{rest.split('@', 1)[0]}"


def discover_publications(publications_dir: Path) -> list[str]:
    return sorted(
        child.name
        for child in publications_dir.iterdir()
        if (child / "build.gradle.kts").is_file()
    )


def pom_lower_jar_candidates(pom: Path) -> set[str]:
    """Return the artifactIds of the POM's dependencies that share its groupId."""
    root = ElementTree.parse(pom).getroot()
    group_id = root.findtext("m:groupId", default="", namespaces=_POM_NAMESPACE)
    return {
        dependency.findtext("m:artifactId", default="", namespaces=_POM_NAMESPACE)
        for dependency in root.findall("m:dependencies/m:dependency", _POM_NAMESPACE)
        if dependency.findtext("m:groupId", default="", namespaces=_POM_NAMESPACE) == group_id
    }


def _lower_jars(name: str, direct_lower_jars: dict[str, set[str]]) -> tuple[str, ...]:
    found: set[str] = set()
    pending = list(direct_lower_jars.get(name, ()))
    while pending:
        lower = pending.pop()
        if lower not in found:
            found.add(lower)
            pending.extend(direct_lower_jars.get(lower, ()))
    return tuple(sorted(found))


def compare_publications(
    publications_dir: Path,
    jar_scans_dir: Path,
    severity_floor: str,
) -> list[JarParity]:
    names = discover_publications(publications_dir)
    problems: dict[str, list[str]] = {name: [] for name in names}
    direct_lower_jars: dict[str, set[str]] = {}
    sbom_scans: dict[str, dict] = {}
    jar_scans: dict[str, dict] = {}
    bundled_modules: dict[str, set[str]] = {}
    for name in names:
        pom = publications_dir / name / GENERATED_POM
        modules = publications_dir / name / BUNDLED_MODULES
        sbom_scan = publications_dir / name / SBOM_SCAN_REPORT
        jar_scan = jar_scans_dir / f"{name}.json"
        if not pom.is_file():
            problems[name].append(f"no POM for `{name}`")
        else:
            try:
                direct_lower_jars[name] = pom_lower_jar_candidates(pom) & set(names)
            except (OSError, ElementTree.ParseError, DefusedXmlException):
                problems[name].append(f"unreadable POM for `{name}`")
        if not modules.is_file():
            problems[name].append(f"no bundled-module list for `{name}`")
        else:
            try:
                bundled_modules[name] = set(modules.read_text().split())
            except (OSError, UnicodeDecodeError):
                problems[name].append(f"unreadable bundled-module list for `{name}`")
        for label, path, reports in (
            ("SBOM scan report", sbom_scan, sbom_scans),
            ("jar scan report", jar_scan, jar_scans),
        ):
            if not path.is_file():
                problems[name].append(f"no {label} for `{name}`")
                continue
            try:
                reports[name] = read_trivy_report(path)
            except UnreadableReport as exc:
                print(f"warning: {exc}", file=sys.stderr)
                problems[name].append(f"unreadable {label} for `{name}`")

    def findings(reports: dict[str, dict], jars: Iterable[str]) -> list[Finding]:
        return filter_by_severity(
            [f for jar in jars for f in findings_in(reports[jar])], severity_floor
        )

    rows = []
    for name in names:
        lower_jars = _lower_jars(name, direct_lower_jars)
        stack = (name, *lower_jars)
        missing = [problem for jar in stack for problem in problems[jar]]
        if missing:
            rows.append(JarParity(name, lower_jars, missing_inputs=tuple(missing)))
            continue
        bundled = set().union(*(bundled_modules[jar] for jar in stack))
        sbom_findings = findings(sbom_scans, stack)
        unbundled = {
            _finding_key(f): f
            for f in sbom_findings
            if (module := _maven_module(f)) is not None and module not in bundled
        }
        not_bundled = frozenset(unbundled.values())
        gaps, fat_jar_only, common = compare(
            [f for f in sbom_findings if _finding_key(f) not in unbundled], findings(jar_scans, stack)
        )
        rows.append(
            JarParity(
                name,
                lower_jars,
                gaps=frozenset(gaps),
                not_bundled=not_bundled,
                common=frozenset(common),
                fat_jar_only=frozenset(fat_jar_only),
                jar_packages=frozenset(
                    package for jar in stack for package in packages_in(jar_scans[jar])
                ),
            )
        )
    return rows


def _findings_by_jar(rows: list[JarParity], field: str) -> list[tuple[Finding, list[str]]]:
    by_key: dict[tuple[str, str], tuple[Finding, list[str]]] = {}
    for row in rows:
        for finding in getattr(row, field):
            by_key.setdefault(_finding_key(finding), (finding, []))[1].append(row.name)
    return sorted(by_key.values(), key=lambda entry: (entry[0].severity, entry[0].cve_id, entry[0].purl))


def _format_table(
    entries: list[tuple[Finding, list[str]]],
    jar_packages: dict[str, frozenset[tuple[str, str]]] | None = None,
) -> str:
    if not entries:
        return "_(none)_"
    jar_scan_column = jar_packages is not None
    columns = ["CVE", "Severity", "Package", "Installed", "Fixed"]
    columns += ["Jar scans see"] if jar_scan_column else []
    columns += ["Jars", "Title"]
    lines = ["| " + " | ".join(columns) + " |", "|" + " --- |" * len(columns)]
    for f, jars in entries:
        fixed = f.fixed_version or "_unfixed_"
        title = (f.title or "").replace("|", "\\|")
        jar_list = ", ".join(f"`{jar}`" for jar in jars)
        row = f"| {f.cve_id} | {f.severity} | {f.pkg_name} | {f.installed_version} | {fixed} |"
        if jar_scan_column:
            versions = sorted(
                {version for jar in jars for name, version in jar_packages[jar] if name == f.pkg_name}
            )
            row += f" {', '.join(versions) or '_not detected_'} |"
        lines.append(f"{row} {jar_list} | {title} |")
    return "\n".join(lines)


def _jar_row(row: JarParity) -> str:
    lower_jars = ", ".join(f"`{jar}`" for jar in row.lower_jars) or "—"
    if row.missing_inputs:
        return f"| `{row.name}` | {lower_jars} | ⚠️ Not compared: {'; '.join(row.missing_inputs)} | | | |"
    return (
        f"| `{row.name}` | {lower_jars} | {len(row.gaps)} | {len(row.common)} | "
        f"{len(row.fat_jar_only)} | {len(row.not_bundled)} |"
    )


def render_markdown(rows: list[JarParity], severity_floor: str) -> str:
    lines = [
        STICKY_COMMENT_MARKER,
        "## Security scan: CVE parity report",
        "",
        f"**Severity floor:** `{severity_floor.upper()}+`",
        "",
        "Each row scans a published jar together with the lower jars its POM depends on. "
        "A parity gap is a CVE in a bundled module that the SBOM scans list but no jar scan finds, "
        "so consumers' scanners miss it.",
        "",
        "| Jar | Lower jars | Parity gaps | Common | Fat-JAR-only | Not bundled |",
        "| --- | --- | --- | --- | --- | --- |",
        *map(_jar_row, rows),
        "",
        "### Parity gaps",
        "",
        _format_table(
            _findings_by_jar(rows, "gaps"), {row.name: row.jar_packages for row in rows}
        ),
        "",
        "<details><summary><strong>Common findings</strong></summary>",
        "",
        _format_table(_findings_by_jar(rows, "common")),
        "",
        "</details>",
        "",
        "<details><summary><strong>Not bundled</strong></summary>",
        "",
        "_CVEs a jar's SBOM lists in modules with no class in any jar of its stack._",
        "",
        _format_table(_findings_by_jar(rows, "not_bundled")),
        "",
        "</details>",
        "",
        "<details><summary><strong>Fat-JAR-only findings</strong></summary>",
        "",
        _format_table(_findings_by_jar(rows, "fat_jar_only")),
        "",
        "</details>",
    ]
    return "\n".join(lines)


def render_json(rows: list[JarParity], severity_floor: str) -> str:
    def findings(values: Iterable[Finding]) -> list[dict]:
        return [asdict(f) for f in sorted(values, key=_finding_key)]

    payload = {
        "severity_floor": severity_floor.upper(),
        "jars": [
            {
                "name": row.name,
                "lower_jars": list(row.lower_jars),
                "missing_inputs": list(row.missing_inputs),
                "parity_gaps": findings(row.gaps),
                "not_bundled": findings(row.not_bundled),
                "common": findings(row.common),
                "fat_jar_only": findings(row.fat_jar_only),
            }
            for row in rows
        ],
    }
    return json.dumps(payload, indent=2, sort_keys=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare each publication's SBOM scan with the Trivy scans of its jar and lower jars.",
    )
    parser.add_argument(
        "--publications-dir",
        required=True,
        help="Directory with one subproject per publication (each with a build.gradle.kts).",
    )
    parser.add_argument(
        "--jar-scans-dir",
        required=True,
        help="Directory with one Trivy JSON report per publication jar, named <publication>.json.",
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

    publications_dir = Path(args.publications_dir)
    if not publications_dir.is_dir():
        print(f"error: not a directory: {publications_dir}", file=sys.stderr)
        return 2
    rows = compare_publications(
        publications_dir, Path(args.jar_scans_dir), args.severity_floor
    )
    if not rows:
        print(f"error: no publications under {publications_dir}", file=sys.stderr)
        return 2

    if args.output_format == "markdown":
        rendered = render_markdown(rows, args.severity_floor)
    else:
        rendered = render_json(rows, args.severity_floor)

    if args.output:
        Path(args.output).write_text(rendered + "\n")
    else:
        print(rendered)

    not_compared = [row.name for row in rows if row.missing_inputs]
    if not_compared:
        print(
            f"::warning::Not compared: {', '.join(not_compared)}. See the CVE parity report.",
            file=sys.stdout if args.output else sys.stderr,
        )

    return 1 if any(row.gaps or row.missing_inputs for row in rows) else 0


if __name__ == "__main__":
    sys.exit(main())
