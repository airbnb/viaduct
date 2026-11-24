import contextlib
import io
import json
import os
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from compare_cve_findings import (
    BUNDLED_MODULES,
    GENERATED_POM,
    SBOM_SCAN_REPORT,
    STICKY_COMMENT_MARKER,
    SUPPORTED_TRIVY_SCHEMA_VERSIONS,
    Finding,
    compare,
    compare_publications,
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

    def test_ignores_purl_qualifiers(self):
        fine = replace(self._f("CVE-Q"), purl="pkg:maven/x/y@1.0?type=jar")
        fat = self._f("CVE-Q")
        only_in_fine, only_in_fat, common = compare([fine], [fat])
        self.assertEqual((only_in_fine, only_in_fat, common), (set(), set(), {fine}))

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


class _PublicationsTree:
    def __init__(self, test: unittest.TestCase):
        tmp = tempfile.TemporaryDirectory()
        test.addCleanup(tmp.cleanup)
        self.publications = Path(tmp.name) / "publications"
        self.jar_scans = Path(tmp.name) / "jar-scans"
        self.jar_scans.mkdir(parents=True)

    def add(
        self,
        name: str,
        lower_jars=(),
        sbom=(),
        jar=(),
        jar_packages=(),
        other_group_dependencies=(),
        bundled=None,
    ) -> "_PublicationsTree":
        project = self.publications / name
        (project / GENERATED_POM).parent.mkdir(parents=True)
        (project / SBOM_SCAN_REPORT).parent.mkdir(parents=True)
        (project / "build.gradle.kts").touch()
        dependencies = "".join(
            f"<dependency><groupId>{group}</groupId><artifactId>{artifact}</artifactId></dependency>"
            for group, artifact in [("com.airbnb.viaduct", lower) for lower in lower_jars]
            + [tuple(coordinate.split(":")) for coordinate in other_group_dependencies]
        )
        (project / GENERATED_POM).write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>com.airbnb.viaduct</groupId>'
            f"<dependencies>{dependencies}</dependencies></project>"
        )
        (project / SBOM_SCAN_REPORT).write_text(json.dumps(_trivy_doc(list(sbom))))
        modules = [v["PkgName"] for v in sbom] if bundled is None else bundled
        (project / BUNDLED_MODULES).write_text("\n".join(modules) + "\n")
        jar_scan = _trivy_doc(list(jar))
        jar_scan["Results"][0]["Packages"] = [{"Name": n, "Version": v} for n, v in jar_packages]
        (self.jar_scans / f"{name}.json").write_text(json.dumps(jar_scan))
        return self

    def rows(self):
        return {row.name: row for row in compare_publications(self.publications, self.jar_scans, "HIGH")}

    def main(self, *extra: str) -> int:
        return main([
            "--publications-dir", str(self.publications),
            "--jar-scans-dir", str(self.jar_scans),
            "--output", os.devnull,
            *extra,
        ])


_GUAVA = _vuln("CVE-GUAVA", "com.google.guava:guava", "33.3.1-jre")
_JACKSON = _vuln("CVE-JACKSON", "com.fasterxml.jackson.core:jackson-core", "2.17.3")
_MICROMETER = _vuln("CVE-MICROMETER", "io.micrometer:micrometer-core", "1.10.8")


def _cves(findings) -> set[str]:
    return {f.cve_id for f in findings}


class TestComparePublications(unittest.TestCase):

    def test_compares_each_jar_with_its_lower_jars(self):
        rows = (
            _PublicationsTree(self)
            .add("api", sbom=[_JACKSON], jar=[_GUAVA])
            .add("runtime", lower_jars=["api"], sbom=[_GUAVA], jar=[_JACKSON])
            .rows()
        )
        self.assertEqual(_cves(rows["runtime"].common), {"CVE-GUAVA", "CVE-JACKSON"})
        self.assertEqual(rows["runtime"].gaps, frozenset())

    def test_follows_lower_jars_transitively(self):
        rows = (
            _PublicationsTree(self)
            .add("api")
            .add("runtime", lower_jars=["api"])
            .add("test-fixtures", lower_jars=["runtime"])
            .rows()
        )
        self.assertEqual(rows["test-fixtures"].lower_jars, ("api", "runtime"))

    def test_ignores_dependencies_outside_the_publications_group(self):
        rows = (
            _PublicationsTree(self)
            .add("api")
            .add("buildtime", other_group_dependencies=["org.example:api"])
            .rows()
        )
        self.assertEqual(rows["buildtime"].lower_jars, ())

    def test_reports_findings_in_modules_no_jar_in_the_stack_bundles_as_not_bundled(self):
        rows = (
            _PublicationsTree(self)
            .add("api", sbom=[_JACKSON, _GUAVA], bundled=["com.google.guava:guava"])
            .add("runtime", lower_jars=["api"], sbom=[_JACKSON], jar=[_JACKSON])
            .rows()
        )
        self.assertEqual(_cves(rows["api"].gaps), {"CVE-GUAVA"})
        self.assertEqual(_cves(rows["api"].not_bundled), {"CVE-JACKSON"})
        self.assertEqual(_cves(rows["runtime"].gaps), {"CVE-GUAVA"})
        self.assertEqual(rows["runtime"].not_bundled, frozenset())

    def test_keys_bundled_modules_on_the_purl(self):
        without_pkg_name = {k: v for k, v in _GUAVA.items() if k != "PkgName"}
        retitled = dict(_JACKSON, Title="Another title")
        rows = (
            _PublicationsTree(self)
            .add("runtime", sbom=[without_pkg_name, _JACKSON, retitled], bundled=["com.google.guava:guava"])
            .rows()
        )
        self.assertEqual(_cves(rows["runtime"].gaps), {"CVE-GUAVA"})
        self.assertEqual(len(rows["runtime"].not_bundled), 1)

    def test_does_not_compare_a_jar_whose_stack_lacks_a_readable_report(self):
        tree = (
            _PublicationsTree(self)
            .add("api")
            .add("runtime", lower_jars=["api"])
            .add("buildtime")
            .add("javaapi-buildtime")
            .add("javaapi-api")
        )
        (tree.publications / "javaapi-api" / GENERATED_POM).write_text(
            '<!DOCTYPE project [<!ENTITY lower "api">]>'
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>com.airbnb.viaduct</groupId></project>'
        )
        (tree.jar_scans / "api.json").unlink()
        (tree.jar_scans / "buildtime.json").write_text("null")
        (tree.publications / "javaapi-buildtime" / BUNDLED_MODULES).write_bytes(b"\xff\xfe")
        rows = tree.rows()
        self.assertEqual(rows["runtime"].missing_inputs, ("no jar scan report for `api`",))
        self.assertEqual(rows["buildtime"].missing_inputs, ("unreadable jar scan report for `buildtime`",))
        self.assertEqual(
            rows["javaapi-buildtime"].missing_inputs, ("unreadable bundled-module list for `javaapi-buildtime`",)
        )
        self.assertEqual(rows["javaapi-api"].missing_inputs, ("unreadable POM for `javaapi-api`",))
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.assertEqual(tree.main(), 1)
        self.assertIn("::warning::Not compared: api, buildtime, javaapi-api, javaapi-buildtime, runtime.", stdout.getvalue())


class TestMain(unittest.TestCase):

    def test_exit_codes(self):
        clean = _PublicationsTree(self).add("api", sbom=[_GUAVA], jar=[_GUAVA])
        gap = _PublicationsTree(self).add("api", sbom=[_GUAVA])
        low = _PublicationsTree(self).add("api", sbom=[_vuln("CVE-LOW", "x:y", "1.0", severity="LOW")])
        self.assertEqual(clean.main(), 0)
        self.assertEqual(gap.main(), 1)
        self.assertEqual(low.main(), 0)
        self.assertEqual(main(["--publications-dir", "/nonexistent", "--jar-scans-dir", "/nonexistent"]), 2)

    def test_renders_one_row_per_jar_and_the_finding_tables(self):
        tree = (
            _PublicationsTree(self)
            .add(
                "api",
                sbom=[_vuln("CVE-PIPE", "x:y", "1.0", title="bad | title"), _JACKSON],
                jar_packages=[("x:y", "0.9")],
                bundled=["x:y"],
            )
            .add("runtime", lower_jars=["api"])
            .add("test-fixtures", lower_jars=["runtime"])
        )
        (tree.jar_scans / "test-fixtures.json").unlink()
        rows = list(compare_publications(tree.publications, tree.jar_scans, "HIGH"))
        out = render_markdown(rows, "HIGH")
        not_bundled = out[out.index("<strong>Not bundled</strong>"):out.index("<strong>Fat-JAR-only")]
        self.assertTrue(out.startswith(STICKY_COMMENT_MARKER))
        self.assertIn("| `api` | — | 1 | 0 | 0 | 1 |", out)
        self.assertIn("| `test-fixtures` | `api`, `runtime` | ⚠️ Not compared: no jar scan report for `test-fixtures` |", out)
        self.assertIn("| CVE-JACKSON | HIGH | com.fasterxml.jackson.core:jackson-core | 2.17.3 | 1.0.0 | `api`, `runtime` |", not_bundled)
        self.assertIn("| CVE-PIPE | HIGH | x:y | 1.0 | 1.0.0 | 0.9 | `api`, `runtime` | bad \\| title |", out)

    def test_renders_json_per_jar(self):
        tree = _PublicationsTree(self).add("api", sbom=[_GUAVA])
        rows = list(compare_publications(tree.publications, tree.jar_scans, "HIGH"))
        parsed = json.loads(render_json(rows, "HIGH"))
        self.assertEqual(parsed["severity_floor"], "HIGH")
        self.assertEqual([jar["name"] for jar in parsed["jars"]], ["api"])
        self.assertEqual([f["cve_id"] for f in parsed["jars"][0]["parity_gaps"]], ["CVE-GUAVA"])


if __name__ == "__main__":
    unittest.main()
