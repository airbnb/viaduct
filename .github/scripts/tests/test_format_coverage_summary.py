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
        for module in ("engine/api", "tenant/runtime"):
            report_dir = os.path.join(self.tmpdir, module, "build/reports/jacoco/test")
            os.makedirs(report_dir)
            with open(os.path.join(report_dir, "jacocoTestReport.xml"), "w") as f:
                f.write(MINIMAL_XML)

    def test_finds_modules(self):
        modules = find_module_reports(self.tmpdir)
        names = [m[0] for m in modules]
        self.assertIn(":engine:api", names)
        self.assertIn(":tenant:runtime", names)

    def test_sorted_by_name(self):
        modules = find_module_reports(self.tmpdir)
        names = [m[0] for m in modules]
        self.assertEqual(names, sorted(names))

    def test_ignores_aggregate_report(self):
        agg_dir = os.path.join(self.tmpdir, "build/reports/jacoco/testCodeCoverageReport")
        os.makedirs(agg_dir)
        with open(os.path.join(agg_dir, "jacocoTestReport.xml"), "w") as f:
            f.write(MINIMAL_XML)
        modules = find_module_reports(self.tmpdir)
        names = [m[0] for m in modules]
        self.assertNotIn(":build:reports:jacoco:testCodeCoverageReport", names)


class TestFormatSummary(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        for module in ("engine/api", "tenant/runtime"):
            report_dir = os.path.join(self.tmpdir, module, "build/reports/jacoco/test")
            os.makedirs(report_dir)
            with open(os.path.join(report_dir, "jacocoTestReport.xml"), "w") as f:
                f.write(MINIMAL_XML)

    def test_contains_details_tag(self):
        result = format_summary(self.tmpdir)
        self.assertIn("<details>", result)
        self.assertIn("</details>", result)

    def test_contains_module_names(self):
        result = format_summary(self.tmpdir)
        self.assertIn(":engine:api", result)
        self.assertIn(":tenant:runtime", result)

    def test_contains_percentages(self):
        result = format_summary(self.tmpdir)
        self.assertIn("75.0%", result)
        self.assertIn("60.0%", result)

    def test_defaults_collapsed(self):
        result = format_summary(self.tmpdir)
        self.assertIn("<summary>", result)


if __name__ == "__main__":
    unittest.main()
