import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from format_coverage_summary import format_summary, find_module_reports, parse_counter
import xml.etree.ElementTree as ET

MINIMAL_XML = """\
<?xml version="1.0" encoding="UTF-8"?>
<report name="test">
  <counter type="INSTRUCTION" missed="25" covered="75"/>
  <counter type="BRANCH" missed="40" covered="60"/>
</report>
"""


class TestParseCounter(unittest.TestCase):

    def test_instruction_counter(self):
        root = ET.fromstring(MINIMAL_XML)
        covered, total, pct = parse_counter(root, "INSTRUCTION")
        self.assertEqual(covered, 75)
        self.assertEqual(total, 100)
        self.assertAlmostEqual(pct, 75.0)

    def test_branch_counter(self):
        root = ET.fromstring(MINIMAL_XML)
        covered, total, pct = parse_counter(root, "BRANCH")
        self.assertEqual(covered, 60)
        self.assertEqual(total, 100)
        self.assertAlmostEqual(pct, 60.0)

    def test_missing_counter(self):
        root = ET.fromstring(MINIMAL_XML)
        covered, total, pct = parse_counter(root, "COMPLEXITY")
        self.assertEqual(covered, 0)
        self.assertEqual(total, 0)
        self.assertAlmostEqual(pct, 0.0)


class TestFindModuleReports(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.core_dir = os.path.join(self.tmpdir, "core")
        for module in ("engine/api", "tenant/runtime"):
            report_dir = os.path.join(self.core_dir, module, "build/reports/jacoco/test")
            os.makedirs(report_dir)
            with open(os.path.join(report_dir, "jacocoTestReport.xml"), "w") as f:
                f.write(MINIMAL_XML)

    def test_finds_modules(self):
        modules = find_module_reports(self.core_dir)
        names = [m[0] for m in modules]
        self.assertIn(":core:engine:api", names)
        self.assertIn(":core:tenant:runtime", names)

    def test_ignores_aggregate_report(self):
        agg_dir = os.path.join(self.core_dir, "build/reports/jacoco/testCodeCoverageReport")
        os.makedirs(agg_dir)
        with open(os.path.join(agg_dir, "jacocoTestReport.xml"), "w") as f:
            f.write(MINIMAL_XML)
        modules = find_module_reports(self.core_dir)
        names = [m[0] for m in modules]
        self.assertNotIn(":core:build:reports:jacoco:testCodeCoverageReport", names)


class TestFindMultipleDirectories(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        for top, module in [("core", "engine/api"), ("build-logic", "common")]:
            report_dir = os.path.join(
                self.tmpdir, top, module, "build/reports/jacoco/test"
            )
            os.makedirs(report_dir)
            with open(os.path.join(report_dir, "jacocoTestReport.xml"), "w") as f:
                f.write(MINIMAL_XML)

    def test_finds_modules_across_dirs(self):
        core_dir = os.path.join(self.tmpdir, "core")
        bl_dir = os.path.join(self.tmpdir, "build-logic")
        core_modules = find_module_reports(core_dir)
        bl_modules = find_module_reports(bl_dir)
        all_names = [m[0] for m in core_modules + bl_modules]
        self.assertIn(":core:engine:api", all_names)
        self.assertIn(":build-logic:common", all_names)


class TestFormatSummary(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.core_dir = os.path.join(self.tmpdir, "core")
        for module in ("engine/api", "tenant/runtime", "shared/utils"):
            report_dir = os.path.join(self.core_dir, module, "build/reports/jacoco/test")
            os.makedirs(report_dir)
            with open(os.path.join(report_dir, "jacocoTestReport.xml"), "w") as f:
                f.write(MINIMAL_XML)

    def test_contains_details_tag(self):
        result = format_summary([self.core_dir])
        self.assertIn("<details>", result)
        self.assertIn("</details>", result)

    def test_contains_module_names(self):
        result = format_summary([self.core_dir])
        self.assertIn(":core:engine:api", result)
        self.assertIn(":core:tenant:runtime", result)

    def test_contains_percentages(self):
        result = format_summary([self.core_dir])
        self.assertIn("75.0%", result)
        self.assertIn("60.0%", result)

    def test_defaults_collapsed(self):
        result = format_summary([self.core_dir])
        self.assertIn("<summary>", result)

    def test_key_modules_in_top_table(self):
        result = format_summary([self.core_dir])
        self.assertIn("Key Module Coverage", result)
        self.assertIn(":core:engine:api", result)
        self.assertIn(":core:tenant:runtime", result)

    def test_all_modules_in_collapsed_section(self):
        result = format_summary([self.core_dir])
        details_start = result.index("<details>")
        details_section = result[details_start:]
        self.assertIn(":core:shared:utils", details_section)
        self.assertIn(":core:engine:api", details_section)

    def test_no_key_section_when_no_key_modules(self):
        tmpdir2 = tempfile.mkdtemp()
        other_dir = os.path.join(tmpdir2, "build-logic")
        report_dir = os.path.join(other_dir, "common", "build/reports/jacoco/test")
        os.makedirs(report_dir)
        with open(os.path.join(report_dir, "jacocoTestReport.xml"), "w") as f:
            f.write(MINIMAL_XML)
        result = format_summary([other_dir])
        self.assertNotIn("Key Module Coverage", result)
        self.assertIn("<details>", result)

    def test_names_dirs_without_reports(self):
        empty_dir = os.path.join(self.tmpdir, "gradle-plugins")
        os.makedirs(empty_dir)
        result = format_summary([self.core_dir, empty_dir])
        self.assertIn("⚠️ No coverage reports found for: gradle-plugins", result)
        self.assertIn("Key Module Coverage", result)

    def test_no_warning_when_all_dirs_have_reports(self):
        result = format_summary([self.core_dir])
        self.assertNotIn("No coverage reports found for", result)

    def test_warns_when_no_dirs_have_reports(self):
        empty_dir = os.path.join(self.tmpdir, "empty")
        os.makedirs(empty_dir)
        result = format_summary([empty_dir])
        self.assertEqual(result, "⚠️ No per-module coverage reports found.")

    def test_all_table_sorted_alphabetically(self):
        result = format_summary([self.core_dir])
        details_start = result.index("<details>")
        details_section = result[details_start:]
        engine_pos = details_section.index(":core:engine:api")
        shared_pos = details_section.index(":core:shared:utils")
        tenant_pos = details_section.index(":core:tenant:runtime")
        self.assertLess(engine_pos, shared_pos)
        self.assertLess(shared_pos, tenant_pos)


if __name__ == "__main__":
    unittest.main()
