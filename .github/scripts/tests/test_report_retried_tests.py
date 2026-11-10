import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from report_retried_tests import format_report, retried_tests

PASSED = '<testcase name="{0}" classname="a.B"/>'
FAILED = '<testcase name="{0}" classname="a.B"><failure message="boom {1}">trace</failure></testcase>'
SKIPPED = '<testcase name="{0}" classname="a.B"><skipped/></testcase>'


def write_suite(root, module, cases):
    results = Path(root, module, "build", "test-results", "test")
    results.mkdir(parents=True)
    (results / "TEST-a.B.xml").write_text(f"<testsuite>{''.join(cases)}</testsuite>")


class TestRetriedTests(unittest.TestCase):

    def scan(self, cases):
        with tempfile.TemporaryDirectory() as root:
            write_suite(root, "core/engine", cases)
            return retried_tests(root)[0]

    def test_lists_a_test_that_failed_then_passed(self):
        rows = self.scan([FAILED.format("t", ""), FAILED.format("t", ""), PASSED.format("t")])
        self.assertEqual(rows, [("core/engine", "a.B > t", 2, "")])

    def test_ignores_tests_without_a_failure_followed_by_a_pass(self):
        rows = self.scan(
            [FAILED.format("red", "")] * 3
            + [PASSED.format("green"), FAILED.format("t", ""), SKIPPED.format("t")]
            + [FAILED.format("dup", ""), PASSED.format("dup")] * 3
        )
        self.assertEqual(rows, [])

    def test_names_the_root_project(self):
        with tempfile.TemporaryDirectory() as root:
            write_suite(root, "", [FAILED.format("t", ""), PASSED.format("t")])
            self.assertEqual(retried_tests(root)[0][0][0], ".")

    def test_reports_the_kotest_seed(self):
        rows = self.scan([FAILED.format("t", "Repeat this test by using seed -42"), PASSED.format("t")])
        self.assertEqual(rows[0][3], "-42")

    def test_skips_xml_outside_test_results(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "report.xml").write_text("<testsuite>" + FAILED.format("t", "") + PASSED.format("t") + "</testsuite>")
            self.assertEqual(retried_tests(root), ([], 0))


class TestFormatReport(unittest.TestCase):

    def test_tells_no_retry_apart_from_no_xml(self):
        self.assertIn("No test needed a retry.", format_report([], 1))
        self.assertIn("No JUnit XML found.", format_report([], 0))

    def test_escapes_markdown_and_caps_rows(self):
        report = format_report([("m", "a | List<T>", 1, "")] * 51, 1)
        self.assertIn("a \\| List&lt;T&gt;", report)
        self.assertIn("1 more not shown", report)


if __name__ == "__main__":
    unittest.main()
