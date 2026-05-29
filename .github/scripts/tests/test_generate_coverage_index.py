import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from generate_coverage_index import (
    build_index_html,
    coverage_bar,
    find_module_reports,
    parse_counter,
)
import xml.etree.ElementTree as ET

MINIMAL_XML = """\
<?xml version="1.0" encoding="UTF-8"?>
<report name="test">
  <counter type="INSTRUCTION" missed="25" covered="75"/>
  <counter type="BRANCH" missed="40" covered="60"/>
</report>
"""


class TestParseCounter(unittest.TestCase):

    def test_instruction(self):
        root = ET.fromstring(MINIMAL_XML)
        _, _, pct = parse_counter(root, "INSTRUCTION")
        self.assertAlmostEqual(pct, 75.0)

    def test_branch(self):
        root = ET.fromstring(MINIMAL_XML)
        _, _, pct = parse_counter(root, "BRANCH")
        self.assertAlmostEqual(pct, 60.0)

    def test_missing_counter(self):
        root = ET.fromstring(MINIMAL_XML)
        covered, total, pct = parse_counter(root, "COMPLEXITY")
        self.assertEqual(covered, 0)
        self.assertEqual(total, 0)
        self.assertAlmostEqual(pct, 0.0)


class TestCoverageBar(unittest.TestCase):

    def test_dash_for_negative(self):
        self.assertIn("—", coverage_bar(-1.0))

    def test_contains_covered_pct(self):
        bar = coverage_bar(80.0)
        self.assertIn("80% coverage", bar)

    def test_missed_width(self):
        bar = coverage_bar(80.0)
        self.assertIn("width:20.0%", bar)

    def test_covered_width(self):
        bar = coverage_bar(80.0)
        self.assertIn("width:80.0%", bar)

    def test_zero_coverage(self):
        bar = coverage_bar(0.0)
        self.assertIn("0% coverage", bar)
        self.assertIn("width:100.0%", bar)  # missed = 100%


class TestFindModuleReports(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        for module in ("engine/api", "tenant/runtime"):
            report_dir = os.path.join(self.tmpdir, module, "build/reports/jacoco/test")
            os.makedirs(report_dir)
            with open(os.path.join(report_dir, "jacocoTestReport.xml"), "w") as f:
                f.write(MINIMAL_XML)

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def test_finds_two_modules(self):
        results = find_module_reports(self.tmpdir)
        paths = [r[0] for r in results]
        self.assertIn("engine/api", paths)
        self.assertIn("tenant/runtime", paths)

    def test_sorted(self):
        results = find_module_reports(self.tmpdir)
        paths = [r[0] for r in results]
        self.assertEqual(paths, sorted(paths))

    def test_no_html_when_absent(self):
        results = find_module_reports(self.tmpdir)
        for _, _, html_dir in results:
            self.assertIsNone(html_dir)

    def test_html_dir_detected(self):
        module = "engine/api"
        html_dir = os.path.join(self.tmpdir, module, "build/reports/jacoco/test/html")
        os.makedirs(html_dir)
        results = find_module_reports(self.tmpdir)
        entry = next(r for r in results if r[0] == module)
        self.assertIsNotNone(entry[2])


class TestBuildIndexHtml(unittest.TestCase):

    def test_toggle_button_with_data_src(self):
        rows = [("engine/api", 80.0, 70.0, True)]
        html = build_index_html(rows)
        self.assertIn('data-src="engine/api/index.html"', html)
        self.assertIn('class="toggle"', html)

    def test_frame_row_present(self):
        rows = [("engine/api", 80.0, 70.0, True)]
        html = build_index_html(rows)
        self.assertIn('class="frame-row"', html)
        self.assertIn("<iframe", html)

    def test_no_toggle_when_no_html(self):
        rows = [("engine/api", 80.0, 70.0, False)]
        html = build_index_html(rows)
        self.assertNotIn('class="toggle"', html)
        self.assertNotIn("<iframe", html)
        self.assertIn("engine/api", html)

    def test_dash_for_negative_pct(self):
        rows = [("broken/module", -1.0, -1.0, False)]
        html = build_index_html(rows)
        self.assertIn("—", html)

    def test_coverage_bars_present(self):
        rows = [("engine/api", 80.0, 70.0, False)]
        html = build_index_html(rows)
        self.assertIn("80% coverage", html)
        self.assertIn("70% coverage", html)


if __name__ == "__main__":
    unittest.main()
