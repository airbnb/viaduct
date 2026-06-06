import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent.parent))

from format_sbom_summary import (
    component_row,
    extract_license,
    find_sbom_reports,
    format_summary,
    main,
)


def _write_sbom(scan_dir, module, components, *, raw=None):
    """Write a CycloneDX SBOM for `module` under scan_dir/<module>/build/reports/sbom."""
    report_dir = os.path.join(scan_dir, module, "build", "reports", "sbom")
    os.makedirs(report_dir, exist_ok=True)
    path = os.path.join(report_dir, "cyclonedx.json")
    with open(path, "w", encoding="utf-8") as f:
        if raw is not None:
            f.write(raw)
        else:
            json.dump(
                {
                    "bomFormat": "CycloneDX",
                    "specVersion": "1.5",
                    "metadata": {"component": {"name": module}},
                    "components": components,
                },
                f,
            )
    return path


# A golden fixture mirroring real cyclonedx-gradle-plugin 1.10.0 output (trimmed:
# the bulky base64 license `text` blobs are dropped — the formatter ignores them).
GOLDEN_COMPONENTS = [
    # Deliberately out of order to prove the formatter sorts by dependency.
    {
        "type": "library",
        "group": "com.example",
        "name": "beta",
        "version": "2.0.0",
        "licenses": [{"license": {"name": "Custom License"}}],
    },
    {
        "type": "library",
        "group": "com.example",
        "name": "alpha",
        "version": "1.0.0",
        "licenses": [{"license": {"id": "Apache-2.0"}}],
    },
]

GOLDEN_EXPECTED = """\
### SBOM: fat JAR contents

_Dependency components on each publication's runtime classpath (CycloneDX SBOM). Close to, but not byte-identical with, the shaded fat-JAR contents._

| Publication | Components |
|---|---:|
| `api` | 2 |
| **Total** | 2 |

<details>
<summary>api — 2 components</summary>

| Dependency | Version | License |
|---|---|---|
| `com.example:alpha` | 1.0.0 | Apache-2.0 |
| `com.example:beta` | 2.0.0 | Custom License |

</details>
"""


class TestExtractLicense(unittest.TestCase):

    def test_spdx_id(self):
        self.assertEqual(
            extract_license({"licenses": [{"license": {"id": "Apache-2.0"}}]}),
            "Apache-2.0",
        )

    def test_named_license(self):
        self.assertEqual(
            extract_license({"licenses": [{"license": {"name": "Custom BSD"}}]}),
            "Custom BSD",
        )

    def test_id_preferred_over_name(self):
        self.assertEqual(
            extract_license(
                {"licenses": [{"license": {"id": "MIT", "name": "MIT License"}}]}
            ),
            "MIT",
        )

    def test_expression(self):
        self.assertEqual(
            extract_license({"licenses": [{"expression": "(MIT OR Apache-2.0)"}]}),
            "(MIT OR Apache-2.0)",
        )

    def test_multiple_distinct_joined(self):
        self.assertEqual(
            extract_license(
                {
                    "licenses": [
                        {"license": {"id": "MIT"}},
                        {"license": {"id": "Apache-2.0"}},
                    ]
                }
            ),
            "MIT, Apache-2.0",
        )

    def test_multiple_duplicates_deduped(self):
        self.assertEqual(
            extract_license(
                {"licenses": [{"license": {"id": "MIT"}}, {"license": {"id": "MIT"}}]}
            ),
            "MIT",
        )

    def test_no_license_is_dash(self):
        self.assertEqual(extract_license({}), "—")
        self.assertEqual(extract_license({"licenses": []}), "—")


class TestComponentRow(unittest.TestCase):

    def test_group_and_name(self):
        self.assertEqual(
            component_row({"group": "org.x", "name": "y", "version": "1.0"}),
            ("org.x:y", "1.0", "—"),
        )

    def test_name_only_when_group_missing(self):
        self.assertEqual(
            component_row({"name": "y", "version": "1.0"}),
            ("y", "1.0", "—"),
        )

    def test_missing_version_is_dash(self):
        self.assertEqual(
            component_row({"group": "org.x", "name": "y"}),
            ("org.x:y", "—", "—"),
        )


class TestFindSbomReports(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()

    def test_finds_module_and_derives_name(self):
        _write_sbom(self.tmpdir, "api", [])
        _write_sbom(self.tmpdir, "javaapi-runtime", [])
        modules = sorted(m for m, _ in find_sbom_reports(self.tmpdir))
        self.assertEqual(modules, ["api", "javaapi-runtime"])

    def test_ignores_cyclonedx_outside_sbom_dir(self):
        stray = os.path.join(self.tmpdir, "api", "build", "reports", "other")
        os.makedirs(stray)
        with open(os.path.join(stray, "cyclonedx.json"), "w") as f:
            f.write("{}")
        self.assertEqual(find_sbom_reports(self.tmpdir), [])


class TestFormatSummaryGolden(unittest.TestCase):

    def test_golden_full_output(self):
        tmpdir = tempfile.mkdtemp()
        _write_sbom(tmpdir, "api", GOLDEN_COMPONENTS)
        self.assertEqual(format_summary([tmpdir]), GOLDEN_EXPECTED)


class TestFormatSummaryBehavior(unittest.TestCase):

    def test_no_sboms_found(self):
        result = format_summary([tempfile.mkdtemp()])
        self.assertTrue(result.startswith("⚠️ No SBOM reports found"))

    def test_nonexistent_dir_is_skipped(self):
        result = format_summary(["/no/such/dir/at/all"])
        self.assertTrue(result.startswith("⚠️ No SBOM reports found"))

    def test_overview_total_across_modules(self):
        tmpdir = tempfile.mkdtemp()
        _write_sbom(tmpdir, "api", GOLDEN_COMPONENTS)  # 2 components
        _write_sbom(
            tmpdir,
            "runtime",
            [{"group": "g", "name": "n", "version": "1", "licenses": []}],  # 1
        )
        result = format_summary([tmpdir])
        self.assertIn("| `api` | 2 |", result)
        self.assertIn("| `runtime` | 1 |", result)
        self.assertIn("| **Total** | 3 |", result)
        # Modules are sorted: api before runtime.
        self.assertLess(result.index("summary>api"), result.index("summary>runtime"))

    def test_expression_and_missing_license_render(self):
        tmpdir = tempfile.mkdtemp()
        _write_sbom(
            tmpdir,
            "api",
            [
                {"group": "g", "name": "expr", "version": "1", "licenses": [{"expression": "(MIT OR Apache-2.0)"}]},
                {"group": "g", "name": "none", "version": "2"},
            ],
        )
        result = format_summary([tmpdir])
        self.assertIn("| `g:expr` | 1 | (MIT OR Apache-2.0) |", result)
        self.assertIn("| `g:none` | 2 | — |", result)

    def test_empty_components_renders_note(self):
        tmpdir = tempfile.mkdtemp()
        _write_sbom(tmpdir, "bom", [])
        result = format_summary([tmpdir])
        self.assertIn("| `bom` | 0 |", result)
        self.assertIn("_No components on the runtime classpath._", result)

    def test_unreadable_sbom_is_flagged_not_fatal(self):
        tmpdir = tempfile.mkdtemp()
        _write_sbom(tmpdir, "api", [], raw="{ not valid json")
        result = format_summary([tmpdir])
        self.assertIn("| `api` | ⚠️ unreadable |", result)
        self.assertIn("Could not parse SBOM", result)


class TestFormatSummaryMultiRoot(unittest.TestCase):

    def test_same_name_across_roots_no_data_loss(self):
        # Two scan roots that each contain a DISTINCT module named `api`.
        root_a = tempfile.mkdtemp()
        root_b = tempfile.mkdtemp()
        _write_sbom(
            root_a,
            "api",
            [{"group": "g", "name": "only-in-a", "version": "1", "licenses": []}],
        )
        _write_sbom(
            root_b,
            "api",
            [
                {"group": "g", "name": "only-in-b", "version": "2", "licenses": []},
                {"group": "g", "name": "also-in-b", "version": "3", "licenses": []},
            ],
        )
        result = format_summary([root_a, root_b])
        # No data loss: components from BOTH api's are present...
        self.assertIn("`g:only-in-a`", result)
        self.assertIn("`g:only-in-b`", result)
        self.assertIn("`g:also-in-b`", result)
        # ...and the total sums both rather than collapsing to one `api`.
        self.assertIn("| **Total** | 3 |", result)
        # Colliding labels are disambiguated by their scan root.
        self.assertIn(f"api ({root_a})", result)
        self.assertIn(f"api ({root_b})", result)

    def test_overlapping_roots_dedupe_same_file(self):
        # A parent dir and one of its nested module dirs resolve to the SAME file.
        parent = tempfile.mkdtemp()
        _write_sbom(
            parent,
            "api",
            [{"group": "g", "name": "n", "version": "1", "licenses": []}],
        )
        nested = os.path.join(parent, "api")
        result = format_summary([parent, nested])
        # Counted exactly once (path de-dupe), not doubled, not disambiguated.
        self.assertIn("| **Total** | 1 |", result)
        self.assertEqual(result.count("<summary>api"), 1)
        self.assertIn("<summary>api — 1 components</summary>", result)


class TestMain(unittest.TestCase):

    def test_usage_error_without_args(self):
        with mock.patch.object(sys, "argv", ["format_sbom_summary.py"]):
            with self.assertRaises(SystemExit) as ctx:
                main()
        self.assertEqual(ctx.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
