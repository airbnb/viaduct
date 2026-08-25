import sys
import unittest
from io import BytesIO, StringIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from extract_failed_tasks import extract_failed_tasks, main


class TestExtractFailedTasks(unittest.TestCase):

    def test_single_task(self):
        log = "> Task :core:x:javaapi:runtime:compileTestKotlin FAILED\n"
        self.assertEqual([":core:x:javaapi:runtime:compileTestKotlin"], extract_failed_tasks(log))

    def test_no_failing_task(self):
        log = "> Task :core:tenant:api:compileKotlin\nBUILD SUCCESSFUL in 2m\n"
        self.assertEqual([], extract_failed_tasks(log))

    def test_empty_log(self):
        self.assertEqual([], extract_failed_tasks(""))

    def test_crlf_line_endings(self):
        log = "> Task :core:shared:utils:test FAILED\r\n> Task :publications:runtime:check FAILED\r\n"
        self.assertEqual(
            [":core:shared:utils:test", ":publications:runtime:check"],
            extract_failed_tasks(log),
        )

    def test_duplicates_collapse(self):
        log = (
            "> Task :core:shared:utils:test FAILED\n"
            "some other output\n"
            "> Task :core:shared:utils:test FAILED\n"
        )
        self.assertEqual([":core:shared:utils:test"], extract_failed_tasks(log))

    def test_order_is_preserved(self):
        log = (
            "> Task :zebra:test FAILED\n"
            "> Task :alpha:test FAILED\n"
            "> Task :middle:test FAILED\n"
        )
        self.assertEqual([":zebra:test", ":alpha:test", ":middle:test"], extract_failed_tasks(log))

    def test_gradle_test_failure_line_is_not_a_task(self):
        log = "BatchResolverIntegrationTest > batch resolver resolves film characters() FAILED\n"
        self.assertEqual([], extract_failed_tasks(log))

    def test_failed_without_task_prefix_is_ignored(self):
        log = "BUILD FAILED in 4m 29s\nExecution failed for task ':test'.\n"
        self.assertEqual([], extract_failed_tasks(log))

    def test_timestamped_log_lines(self):
        log = "2026-08-24T13:42:38.7729370Z > Task :test FAILED\n"
        self.assertEqual([":test"], extract_failed_tasks(log))

    def test_task_without_project_path(self):
        log = "> Task :test FAILED\n"
        self.assertEqual([":test"], extract_failed_tasks(log))

    def test_ansi_colour_codes_are_stripped(self):
        log = "\x1b[36;1m> Task :core:shared:utils:test FAILED\x1b[0m\n"
        self.assertEqual([":core:shared:utils:test"], extract_failed_tasks(log))

    def test_ansi_codes_adjacent_to_the_task_path(self):
        log = "> Task \x1b[1m:core:tenant:api:compileKotlin\x1b[0m FAILED\n"
        self.assertEqual([":core:tenant:api:compileKotlin"], extract_failed_tasks(log))


class BinaryStdin:
    def __init__(self, data: bytes):
        self.buffer = BytesIO(data)


class TestMain(unittest.TestCase):

    def setUp(self):
        self.stdout = sys.stdout
        sys.stdout = StringIO()

    def tearDown(self):
        sys.stdout = self.stdout
        sys.stdin = sys.__stdin__

    def test_prints_one_task_per_line(self):
        sys.stdin = BinaryStdin(b"> Task :a:test FAILED\n> Task :b:test FAILED\n")
        code = main()
        self.assertEqual(0, code)
        self.assertEqual([":a:test", ":b:test"], sys.stdout.getvalue().splitlines())

    def test_prints_nothing_when_no_task_failed(self):
        sys.stdin = BinaryStdin(b"BUILD SUCCESSFUL in 2m\n")
        code = main()
        self.assertEqual(0, code)
        self.assertEqual("", sys.stdout.getvalue())

    def test_invalid_utf8_does_not_crash(self):
        sys.stdin = BinaryStdin(b"\xff\xfe garbage\n> Task :a:test FAILED\n")
        code = main()
        self.assertEqual(0, code)
        self.assertEqual([":a:test"], sys.stdout.getvalue().splitlines())


if __name__ == "__main__":
    unittest.main()
