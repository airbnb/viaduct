import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from compare_cve_findings import (
    STICKY_COMMENT_MARKER,
    SUPPORTED_TRIVY_SCHEMA_VERSIONS,
    Finding,
    compare,
    filter_by_severity,
    main,
    parse_trivy_json,
    render_json,
    render_markdown,
    unsupported_schema_warning,
)


def _write_trivy_json(payload: dict) -> str:
    fd = tempfile.NamedTemporaryFile(
        mode="w", suffix=".json", delete=False, encoding="utf-8"
    )
    try:
        json.dump(payload, fd)
    finally:
        fd.close()
    return fd.name


def _vuln(
    cve: str,
    pkg: str,
    version: str,
    severity: str = "HIGH",
    fixed: str | None = "1.0.0",
    title: str = "Example vuln",
) -> dict:
    return {
        "VulnerabilityID": cve,
        "PkgName": pkg,
        "PkgIdentifier": {"PURL": f"pkg:maven/{pkg.replace(':', '/')}@{version}"},
        "InstalledVersion": version,
        "FixedVersion": fixed,
        "Severity": severity,
        "Title": title,
    }


def _trivy_doc(vulns: list[dict]) -> dict:
    return {
        "SchemaVersion": 2,
        "Results": [{"Target": "test", "Vulnerabilities": vulns}],
    }


# A representative, full-shape Trivy report (as produced by `trivy sbom --format json`).
# Acts as a golden anchor: if Trivy's output shape changes, whoever updates this sample
# sees the structural diff. Includes extra top-level/nested fields the parser ignores,
# a multi-vuln Result, and a clean Result with null Vulnerabilities.
_REALISTIC_TRIVY_REPORT = {
    "SchemaVersion": 2,
    "CreatedAt": "2026-05-20T12:00:00Z",
    "ArtifactName": "build/reports/sbom/cyclonedx.json",
    "ArtifactType": "cyclonedx",
    "Results": [
        {
            "Target": "Java",
            "Class": "lang-pkgs",
            "Type": "jar",
            "Vulnerabilities": [
                {
                    "VulnerabilityID": "CVE-2023-2976",
                    "PkgName": "com.google.guava:guava",
                    "PkgIdentifier": {
                        "PURL": "pkg:maven/com.google.guava/guava@31.1-jre",
                        "UID": "1a2b3c4d",
                    },
                    "InstalledVersion": "31.1-jre",
                    "FixedVersion": "32.0.0-android",
                    "Status": "fixed",
                    "Severity": "HIGH",
                    "Title": "guava: insecure temporary directory creation",
                    "PrimaryURL": "https://avd.aquasec.com/nvd/cve-2023-2976",
                },
                {
                    "VulnerabilityID": "CVE-2022-42003",
                    "PkgName": "com.fasterxml.jackson.core:jackson-databind",
                    "PkgIdentifier": {
                        "PURL": "pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.13.4",
                    },
                    "InstalledVersion": "2.13.4",
                    "FixedVersion": "2.13.4.2, 2.12.7.1",
                    "Severity": "CRITICAL",
                    "Title": "jackson-databind: deep wrapper array nesting",
                },
            ],
        },
        {
            "Target": "OS Packages",
            "Class": "os-pkgs",
            "Vulnerabilities": None,
        },
    ],
}


class TestParseTrivyJson(unittest.TestCase):

    def setUp(self):
        self._paths: list[str] = []

    def tearDown(self):
        for p in self._paths:
            try:
                os.unlink(p)
            except FileNotFoundError:
                pass

    def _make(self, payload: dict) -> Path:
        path = _write_trivy_json(payload)
        self._paths.append(path)
        return Path(path)

    def test_extracts_findings(self):
        path = self._make(
            _trivy_doc([
                _vuln("CVE-2024-1", "com.google.guava:guava", "32.1.2-jre"),
            ])
        )
        findings = parse_trivy_json(path)
        self.assertEqual(len(findings), 1)
        f = findings[0]
        self.assertEqual(f.cve_id, "CVE-2024-1")
        self.assertEqual(f.purl, "pkg:maven/com.google.guava/guava@32.1.2-jre")
        self.assertEqual(f.severity, "HIGH")
        self.assertEqual(f.pkg_name, "com.google.guava:guava")
        self.assertEqual(f.installed_version, "32.1.2-jre")
        self.assertEqual(f.fixed_version, "1.0.0")
        self.assertEqual(f.title, "Example vuln")

    def test_handles_null_vulnerabilities(self):
        path = self._make({
            "SchemaVersion": 2,
            "Results": [{"Target": "clean", "Vulnerabilities": None}],
        })
        self.assertEqual(parse_trivy_json(path), [])

    def test_handles_missing_results(self):
        path = self._make({"SchemaVersion": 2})
        self.assertEqual(parse_trivy_json(path), [])

    def test_handles_missing_purl(self):
        path = self._make({
            "SchemaVersion": 2,
            "Results": [{
                "Target": "test",
                "Vulnerabilities": [{
                    "VulnerabilityID": "CVE-2024-2",
                    "PkgName": "x:y",
                    "InstalledVersion": "1.0.0",
                    "Severity": "HIGH",
                }],
            }],
        })
        findings = parse_trivy_json(path)
        self.assertEqual(len(findings), 1)
        self.assertEqual(findings[0].purl, "")

    def test_normalizes_severity_case(self):
        path = self._make(
            _trivy_doc([_vuln("CVE-2024-3", "x:y", "1.0", severity="high")])
        )
        self.assertEqual(parse_trivy_json(path)[0].severity, "HIGH")

    def test_parse_realistic_trivy_sample(self):
        path = self._make(_REALISTIC_TRIVY_REPORT)
        findings = parse_trivy_json(path)
        by_cve = {f.cve_id: f for f in findings}
        self.assertEqual(set(by_cve), {"CVE-2023-2976", "CVE-2022-42003"})

        guava = by_cve["CVE-2023-2976"]
        self.assertEqual(guava.purl, "pkg:maven/com.google.guava/guava@31.1-jre")
        self.assertEqual(guava.severity, "HIGH")
        self.assertEqual(guava.pkg_name, "com.google.guava:guava")
        self.assertEqual(guava.installed_version, "31.1-jre")
        self.assertEqual(guava.fixed_version, "32.0.0-android")

        jackson = by_cve["CVE-2022-42003"]
        self.assertEqual(jackson.severity, "CRITICAL")
        self.assertEqual(
            jackson.purl,
            "pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.13.4",
        )

    def test_warns_on_unexpected_schema_version_but_still_parses(self):
        path = self._make({
            "SchemaVersion": 99,
            "Results": [{
                "Target": "test",
                "Vulnerabilities": [_vuln("CVE-2024-9", "x:y", "1.0")],
            }],
        })
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            findings = parse_trivy_json(path)
        # Non-fatal: parsing still succeeds.
        self.assertEqual(len(findings), 1)
        # ...but the drift is surfaced loudly.
        self.assertIn("warning", stderr.getvalue())
        self.assertIn("SchemaVersion", stderr.getvalue())

    def test_no_warning_on_supported_schema_version(self):
        path = self._make(_trivy_doc([_vuln("CVE-2024-4", "x:y", "1.0")]))
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            parse_trivy_json(path)
        self.assertEqual(stderr.getvalue(), "")


class TestSchemaVersionWarning(unittest.TestCase):

    def test_two_is_supported(self):
        self.assertIn(2, SUPPORTED_TRIVY_SCHEMA_VERSIONS)

    def test_returns_none_for_supported(self):
        self.assertIsNone(unsupported_schema_warning({"SchemaVersion": 2}))

    def test_returns_message_for_unsupported(self):
        msg = unsupported_schema_warning({"SchemaVersion": 3})
        self.assertIsNotNone(msg)
        self.assertIn("3", msg)
        self.assertIn("SchemaVersion", msg)

    def test_returns_message_for_missing_version(self):
        self.assertIsNotNone(unsupported_schema_warning({}))

    def test_includes_source_when_provided(self):
        msg = unsupported_schema_warning({"SchemaVersion": 3}, source="report.json")
        self.assertIn("report.json", msg)


class TestFilterBySeverity(unittest.TestCase):

    def _make(self, severity: str, cve: str = "CVE-X") -> Finding:
        return Finding(
            cve_id=cve,
            purl=f"pkg:maven/x/y@1.0#{severity}",
            severity=severity,
            pkg_name="x:y",
            installed_version="1.0",
            fixed_version=None,
            title="",
        )

    def test_keeps_at_or_above_floor(self):
        findings = [
            self._make("LOW", "CVE-1"),
            self._make("MEDIUM", "CVE-2"),
            self._make("HIGH", "CVE-3"),
            self._make("CRITICAL", "CVE-4"),
        ]
        result = filter_by_severity(findings, "HIGH")
        self.assertEqual(
            sorted(f.cve_id for f in result), ["CVE-3", "CVE-4"]
        )

    def test_unknown_severity_excluded_at_high_floor(self):
        result = filter_by_severity([self._make("UNKNOWN")], "HIGH")
        self.assertEqual(result, [])


class TestCompare(unittest.TestCase):

    def _f(self, cve: str, version: str = "1.0") -> Finding:
        return Finding(
            cve_id=cve,
            purl=f"pkg:maven/x/y@{version}",
            severity="HIGH",
            pkg_name="x:y",
            installed_version=version,
            fixed_version=None,
            title="",
        )

    def test_returns_three_disjoint_sets(self):
        a, b, c, d = self._f("A"), self._f("B"), self._f("C"), self._f("D")
        only_in_fine, only_in_fat, common = compare([a, b, c], [b, c, d])
        self.assertEqual(only_in_fine, {a})
        self.assertEqual(only_in_fat, {d})
        self.assertEqual(common, {b, c})

    def test_treats_version_mismatch_as_gap(self):
        fine = self._f("CVE-V", "33.0.0-jre")
        fat = self._f("CVE-V", "32.1.2-jre")
        only_in_fine, only_in_fat, common = compare([fine], [fat])
        self.assertEqual(only_in_fine, {fine})
        self.assertEqual(only_in_fat, {fat})
        self.assertEqual(common, set())

    def test_treats_metadata_divergence_as_common(self):
        fine = Finding(
            cve_id="CVE-DIV",
            purl="pkg:maven/x/y@1.0",
            severity="HIGH",
            pkg_name="x:y",
            installed_version="1.0",
            fixed_version="2.0",
            title="fine title",
        )
        fat = Finding(
            cve_id="CVE-DIV",
            purl="pkg:maven/x/y@1.0",
            severity="HIGH",
            pkg_name="x:y",
            installed_version="1.0",
            fixed_version="2.1",
            title="fat title",
        )
        only_in_fine, only_in_fat, common = compare([fine], [fat])
        self.assertEqual(only_in_fine, set())
        self.assertEqual(only_in_fat, set())
        self.assertEqual(len(common), 1)

    def test_picks_fine_metadata_for_common(self):
        fine = Finding(
            cve_id="CVE-W",
            purl="pkg:maven/x/y@1.0",
            severity="HIGH",
            pkg_name="x:y",
            installed_version="1.0",
            fixed_version="2.0",
            title="fine title",
        )
        fat = Finding(
            cve_id="CVE-W",
            purl="pkg:maven/x/y@1.0",
            severity="HIGH",
            pkg_name="x:y",
            installed_version="1.0",
            fixed_version="2.1",
            title="fat title",
        )
        _, _, common = compare([fine], [fat])
        chosen = next(iter(common))
        self.assertEqual(chosen.title, "fine title")
        self.assertEqual(chosen.fixed_version, "2.0")


class TestRenderMarkdown(unittest.TestCase):

    def _f(self, cve: str) -> Finding:
        return Finding(
            cve_id=cve,
            purl=f"pkg:maven/x/y@1.0#{cve}",
            severity="HIGH",
            pkg_name="x:y",
            installed_version="1.0",
            fixed_version="2.0",
            title="title",
        )

    def test_includes_html_marker(self):
        out = render_markdown([], [], [], "HIGH")
        self.assertTrue(out.startswith(STICKY_COMMENT_MARKER))

    def test_lists_gaps_in_first_section(self):
        gap = self._f("CVE-GAP")
        out = render_markdown([gap], [], [], "HIGH")
        gaps_idx = out.index("### Parity gaps")
        common_section_idx = out.index("<strong>Common findings</strong>")
        self.assertLess(gaps_idx, common_section_idx)
        gaps_section = out[gaps_idx:common_section_idx]
        self.assertIn("CVE-GAP", gaps_section)

    def test_renders_empty_state(self):
        out = render_markdown([], [], [], "HIGH")
        self.assertEqual(out.count("_(none)_"), 3)

    def test_escapes_pipes_in_titles(self):
        f = Finding(
            cve_id="CVE-X",
            purl="pkg:maven/x/y@1",
            severity="HIGH",
            pkg_name="x:y",
            installed_version="1",
            fixed_version=None,
            title="bad | title",
        )
        out = render_markdown([f], [], [], "HIGH")
        self.assertIn("bad \\| title", out)


class TestRenderJson(unittest.TestCase):

    def test_round_trips(self):
        f = Finding(
            cve_id="CVE-1",
            purl="pkg:maven/x/y@1.0",
            severity="HIGH",
            pkg_name="x:y",
            installed_version="1.0",
            fixed_version=None,
            title="t",
        )
        out = render_json([f], [], [], "HIGH")
        parsed = json.loads(out)
        self.assertEqual(parsed["severity_floor"], "HIGH")
        self.assertEqual(len(parsed["parity_gaps"]), 1)
        self.assertEqual(parsed["parity_gaps"][0]["cve_id"], "CVE-1")
        self.assertEqual(parsed["common"], [])
        self.assertEqual(parsed["fat_jar_only"], [])


class TestMain(unittest.TestCase):

    def setUp(self):
        self._paths: list[str] = []

    def tearDown(self):
        for p in self._paths:
            try:
                os.unlink(p)
            except FileNotFoundError:
                pass

    def _scan(self, vulns: list[dict]) -> str:
        path = _write_trivy_json(_trivy_doc(vulns))
        self._paths.append(path)
        return path

    def test_returns_0_on_parity(self):
        v = _vuln("CVE-A", "com.google.guava:guava", "32.1.2-jre")
        fine = self._scan([v])
        fat = self._scan([v])
        rc = main([
            "--fine-grained", fine,
            "--fat-jar", fat,
            "--output-format", "json",
            "--output", os.devnull,
        ])
        self.assertEqual(rc, 0)

    def test_returns_1_on_gaps(self):
        gap = _vuln("CVE-GAP", "com.google.guava:guava", "32.1.2-jre")
        fine = self._scan([gap])
        fat = self._scan([])
        rc = main([
            "--fine-grained", fine,
            "--fat-jar", fat,
            "--output-format", "json",
            "--output", os.devnull,
        ])
        self.assertEqual(rc, 1)

    def test_returns_2_on_missing_input(self):
        fat = self._scan([])
        rc = main([
            "--fine-grained", "/nonexistent/path/does-not-exist.json",
            "--fat-jar", fat,
        ])
        self.assertEqual(rc, 2)

    def test_severity_floor_filters_out_low(self):
        low = _vuln(
            "CVE-LOW", "com.google.guava:guava", "32.1.2-jre", severity="LOW"
        )
        fine = self._scan([low])
        fat = self._scan([])
        rc = main([
            "--fine-grained", fine,
            "--fat-jar", fat,
            "--severity-floor", "HIGH",
            "--output-format", "json",
            "--output", os.devnull,
        ])
        self.assertEqual(rc, 0)


if __name__ == "__main__":
    unittest.main()
