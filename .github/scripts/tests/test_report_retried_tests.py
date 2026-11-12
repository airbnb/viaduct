import re
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from report_retried_tests import MAX_ATTEMPTS, Report, format_report, retried_tests

PASSED = '<testcase name="{0}" classname="a.B"/>'
FAILED = '<testcase name="{0}" classname="a.B"><failure message="boom {1}">trace</failure></testcase>'
ERRORED = '<testcase name="{0}" classname="a.B"><error message="boom"/></testcase>'
SKIPPED = '<testcase name="{0}" classname="a.B"><skipped/></testcase>'
RETRY_CONFIG = Path(__file__).parents[3] / "build-logic/src/main/kotlin/conventions/test-retry.gradle.kts"


def write_suite(root, module, cases, timestamp=""):
    results = Path(root, module, "build", "test-results", "test")
    results.mkdir(parents=True)
    (results / "TEST-a.B.xml").write_text(f'<testsuite timestamp="{timestamp}">{"".join(cases)}</testsuite>')
    return results


class TestRetriedTests(unittest.TestCase):

    def scan(self, cases):
        with tempfile.TemporaryDirectory() as root:
            write_suite(root, "core/engine", cases)
            return retried_tests(root)

    def test_lists_a_test_that_failed_then_passed(self):
        rows = self.scan([FAILED.format("t", ""), ERRORED.format("t"), PASSED.format("t")]).rows
        self.assertEqual(rows, [("core/engine", "a.B > t", 2, "")])

    def test_ignores_tests_without_a_failure_followed_by_a_pass(self):
        report = self.scan(
            [FAILED.format("red", "")] * 3
            + [PASSED.format("green"), FAILED.format("t", ""), SKIPPED.format("t")]
            + [FAILED.format("dup", ""), PASSED.format("dup")] * 3
        )
        self.assertEqual((report.rows, report.retries_off), ([], []))

    def test_skips_a_task_whose_failed_test_was_never_retried(self):
        report = self.scan([FAILED.format("red", ""), FAILED.format("dup", ""), PASSED.format("dup")])
        self.assertEqual((report.rows, report.retries_off), ([], ["core/engine"]))

    def test_skips_suites_that_ran_before_the_run_started(self):
        with tempfile.TemporaryDirectory() as root:
            cases = [FAILED.format("t", ""), PASSED.format("t")]
            write_suite(root, "old", cases, "2026-11-11T14:30:29.924Z")
            write_suite(root, "new", cases, "2026-11-12T07:41:02.103Z")
            report = retried_tests(root, datetime.fromisoformat("2026-11-12T07:35:05Z"))
            self.assertEqual((report.rows, report.cached), ([("new", "a.B > t", 1, "")], 1))

    def test_names_the_root_project(self):
        with tempfile.TemporaryDirectory() as root:
            write_suite(root, "", [FAILED.format("t", ""), PASSED.format("t")])
            self.assertEqual(retried_tests(root).rows[0][0], ".")

    def test_reports_the_kotest_seed(self):
        rows = self.scan([FAILED.format("t", "Repeat this test by using seed -42"), PASSED.format("t")]).rows
        self.assertEqual(rows[0][3], "-42")

    def test_skips_xml_outside_test_results(self):
        with tempfile.TemporaryDirectory() as root:
            Path(root, "report.xml").write_text("<testsuite>" + FAILED.format("t", "") + PASSED.format("t") + "</testsuite>")
            self.assertEqual(retried_tests(root), Report())

    def test_skips_unreadable_xml(self):
        with tempfile.TemporaryDirectory() as root:
            results = write_suite(root, "core/engine", [FAILED.format("t", ""), PASSED.format("t")])
            (results / "TEST-entities.xml").write_text('<!DOCTYPE t [<!ENTITY a "a">]><testsuite>&a;</testsuite>')
            (results / "TEST-truncated.xml").write_text("<testsuite><testcase")
            report = retried_tests(root)
            self.assertEqual((report.rows, report.scanned), ([("core/engine", "a.B > t", 1, "")], 1))

    def test_max_attempts_matches_the_gradle_retry_config(self):
        max_retries = re.search(r"maxRetries\.set\(if \(onCi\) (\d+)", RETRY_CONFIG.read_text())
        self.assertEqual(int(max_retries.group(1)) + 1, MAX_ATTEMPTS)


class TestFormatReport(unittest.TestCase):

    def test_tells_no_retry_apart_from_no_xml(self):
        self.assertIn("No test passed after a retry.", format_report(Report(scanned=1)))
        self.assertIn("No JUnit XML found.", format_report(Report()))

    def test_names_what_was_not_checked(self):
        report = format_report(Report(cached=2, retries_off=["core/engine"]))
        self.assertIn("Not checked: core/engine has a failed test that was never retried", report)
        self.assertIn("Not checked: 2 suite(s) restored from the build cache.", report)

    def test_escapes_markdown_and_caps_rows(self):
        report = format_report(Report(rows=[("m", "a | List<T>", 1, "")] * 51, scanned=1))
        self.assertIn("a \\| List&lt;T&gt;", report)
        self.assertIn("1 more not shown", report)


if __name__ == "__main__":
    unittest.main()
