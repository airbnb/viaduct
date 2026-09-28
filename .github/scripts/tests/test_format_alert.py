import json
import sys
import unittest
from io import StringIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from format_alert import MAX_ALERT_CHARS, format_alert, format_attempt_label, format_summary, format_task_lines, main


BASE = {
    "branch": "main",
    "server_url": "https://github.com",
    "repository": "example/repo",
    "jobs": [{"name": "Build and Test", "run_id": "123"}],
}

EXPECTED_URL = "https://github.com/example/repo/actions/runs/123"


class TestFormatAlertSingleJob(unittest.TestCase):

    def test_single_job_contains_red_circle(self):
        self.assertIn(":red_circle:", format_alert(BASE))

    def test_single_job_contains_job_name(self):
        self.assertIn("Build and Test", format_alert(BASE))

    def test_single_job_contains_branch(self):
        self.assertIn("`main`", format_alert(BASE))

    def test_single_job_contains_url(self):
        self.assertIn(EXPECTED_URL, format_alert(BASE))

    def test_single_job_is_one_line(self):
        self.assertEqual(1, len(format_alert(BASE).splitlines()))

    def test_single_job_url_construction(self):
        data = {
            "branch": "main",
            "server_url": "https://github.com",
            "repository": "org/proj",
            "jobs": [{"name": "Test", "run_id": "999"}],
        }
        self.assertIn("https://github.com/org/proj/actions/runs/999", format_alert(data))

    def test_single_job_no_optional_fields(self):
        result = format_alert(BASE)
        self.assertNotIn("commit", result)
        self.assertNotIn("pushed by", result)

    def test_single_job_with_sha_and_actor(self):
        data = {**BASE, "sha": "abc1234567", "actor": "raymie"}
        result = format_alert(data)
        self.assertIn("`abc1234`", result)
        self.assertIn("raymie", result)

    def test_single_job_sha_truncated_to_7(self):
        data = {**BASE, "sha": "abc1234567890"}
        self.assertIn("`abc1234`", format_alert(data))

    def test_single_job_with_sha_only(self):
        data = {**BASE, "sha": "abc1234567"}
        result = format_alert(data)
        self.assertIn("`abc1234`", result)
        self.assertNotIn("by ", result)

    def test_single_job_with_actor_only(self):
        data = {**BASE, "actor": "raymie"}
        result = format_alert(data)
        self.assertIn("raymie", result)
        self.assertNotIn("commit", result)


class TestFormatAlertMultiJob(unittest.TestCase):

    MULTI = {
        "branch": "main",
        "server_url": "https://github.com",
        "repository": "example/repo",
        "jobs": [
            {"name": "Build and Test", "run_id": "111"},
            {"name": "Demo App Tests", "run_id": "222"},
            {"name": "API Compatibility", "run_id": "333"},
        ],
    }

    def test_multi_job_header_contains_red_circle(self):
        lines = format_alert(self.MULTI).splitlines()
        self.assertIn(":red_circle:", lines[0])

    def test_multi_job_header_contains_branch(self):
        lines = format_alert(self.MULTI).splitlines()
        self.assertIn("`main`", lines[0])

    def test_multi_job_header_does_not_contain_job_name(self):
        lines = format_alert(self.MULTI).splitlines()
        self.assertNotIn("Build and Test", lines[0])

    def test_multi_job_produces_bullet_list(self):
        lines = format_alert(self.MULTI).splitlines()
        # header + one bullet per job
        self.assertEqual(4, len(lines))
        for line in lines[1:]:
            self.assertTrue(line.startswith("•"), f"Expected bullet: {line!r}")

    def test_multi_job_each_bullet_has_name_and_url(self):
        result = format_alert(self.MULTI)
        self.assertIn("Build and Test", result)
        self.assertIn("https://github.com/example/repo/actions/runs/111", result)
        self.assertIn("Demo App Tests", result)
        self.assertIn("https://github.com/example/repo/actions/runs/222", result)
        self.assertIn("API Compatibility", result)
        self.assertIn("https://github.com/example/repo/actions/runs/333", result)

    def test_multi_job_with_sha_and_actor(self):
        data = {**self.MULTI, "sha": "deadbeef12", "actor": "bob"}
        lines = format_alert(data).splitlines()
        self.assertIn("`deadbee`", lines[0])
        self.assertIn("bob", lines[0])

    def test_multi_job_attempt_label_on_header_only(self):
        data = {**self.MULTI, "attempt": 2}
        lines = format_alert(data).splitlines()
        self.assertIn(", attempt 2", lines[0])
        for line in lines[1:]:
            self.assertNotIn("attempt", line)


class TestAttemptLabel(unittest.TestCase):

    def test_absent_attempt_is_unlabeled(self):
        self.assertNotIn("attempt", format_alert(BASE))

    def test_first_attempt_is_unlabeled(self):
        self.assertNotIn("attempt", format_alert({**BASE, "attempt": 1}))

    def test_second_attempt_is_labeled(self):
        self.assertIn(", attempt 2", format_alert({**BASE, "attempt": 2}))

    def test_attempt_as_string_is_labeled(self):
        self.assertIn(", attempt 3", format_alert({**BASE, "attempt": "3"}))

    def test_unparseable_attempt_is_unlabeled(self):
        self.assertNotIn("attempt", format_alert({**BASE, "attempt": ""}))

    def test_label_precedes_the_url(self):
        result = format_alert({**BASE, "sha": "abc1234567", "actor": "raymie", "attempt": 2})
        self.assertLess(result.index(", attempt 2"), result.index(EXPECTED_URL))

    def test_label_follows_the_actor(self):
        result = format_alert({**BASE, "sha": "abc1234567", "actor": "raymie", "attempt": 2})
        self.assertLess(result.index("raymie"), result.index(", attempt 2"))

    def test_label_without_sha_or_actor(self):
        self.assertIn("`main`, attempt 2", format_alert({**BASE, "attempt": 2}))

    def test_label_helper_rejects_none(self):
        self.assertEqual("", format_attempt_label(None))


class TestOutcome(unittest.TestCase):

    MULTI = {
        "branch": "main",
        "server_url": "https://github.com",
        "repository": "example/repo",
        "jobs": [
            {"name": "Build and Test", "run_id": "111"},
            {"name": "Demo App Tests", "run_id": "222"},
        ],
    }

    def test_absent_outcome_reads_as_failure(self):
        result = format_alert(BASE)
        self.assertIn(":red_circle:", result)
        self.assertIn("failed on", result)

    def test_explicit_failure_matches_the_default(self):
        self.assertEqual(format_alert(BASE), format_alert({**BASE, "outcome": "failure"}))

    def test_retry_success_single_job(self):
        result = format_alert({**BASE, "outcome": "retry_success"})
        self.assertIn(":green_circle:", result)
        self.assertIn("Build and Test passed on retry on `main`", result)
        self.assertNotIn("failed", result)

    def test_retry_success_multi_job_header(self):
        lines = format_alert({**self.MULTI, "outcome": "retry_success"}).splitlines()
        self.assertEqual(":green_circle: CI passed on retry on `main`", lines[0])

    def test_retrying_is_accepted(self):
        self.assertIn("Build and Test is being retried on `main`", format_alert({**BASE, "outcome": "retrying"}))

    def test_unknown_outcome_is_rejected(self):
        sys.stdin = StringIO(json.dumps({**BASE, "outcome": "flaky"}))
        self.assertEqual(main(), 1)
        sys.stdin = sys.__stdin__


class TestFailingTasks(unittest.TestCase):

    WITH_TASKS = {
        "branch": "main",
        "server_url": "https://github.com",
        "repository": "example/repo",
        "jobs": [
            {
                "name": "build-and-test / Test (Java 17) ubuntu-latest",
                "run_id": "123",
                "tasks": [":core:x:javaapi:runtime:compileTestKotlin"],
            }
        ],
    }

    def test_no_tasks_anywhere_keeps_the_single_line_form(self):
        self.assertEqual(1, len(format_alert(BASE).splitlines()))

    def test_no_tasks_anywhere_keeps_the_inline_bullet_form(self):
        multi = {**BASE, "jobs": [
            {"name": "A", "run_id": "1"},
            {"name": "B", "run_id": "2"},
        ]}
        self.assertIn("• A: https://github.com/example/repo/actions/runs/1", format_alert(multi))

    def test_empty_task_list_is_treated_as_no_tasks(self):
        data = {**BASE, "jobs": [{"name": "Build and Test", "run_id": "123", "tasks": []}]}
        self.assertEqual(format_alert(BASE), format_alert(data))

    def test_one_job_with_tasks_uses_the_header_form(self):
        lines = format_alert(self.WITH_TASKS).splitlines()
        self.assertEqual(":red_circle: CI failed on `main`", lines[0])
        self.assertEqual("• build-and-test / Test (Java 17) ubuntu-latest", lines[1])
        self.assertEqual("  `:core:x:javaapi:runtime:compileTestKotlin`", lines[2])
        self.assertEqual("  https://github.com/example/repo/actions/runs/123", lines[3])

    def test_tasks_are_capped_at_three_with_an_overflow_count(self):
        data = {**self.WITH_TASKS}
        data["jobs"] = [{**data["jobs"][0], "tasks": [f":t{n}" for n in range(9)]}]
        lines = format_alert(data).splitlines()
        self.assertIn("  `:t0`", lines)
        self.assertIn("  `:t1`", lines)
        self.assertIn("  `:t2` +6 more", lines)
        self.assertNotIn("  `:t3`", lines)

    def test_exactly_three_tasks_has_no_overflow_count(self):
        data = {**self.WITH_TASKS}
        data["jobs"] = [{**data["jobs"][0], "tasks": [":a", ":b", ":c"]}]
        self.assertNotIn("more", format_alert(data))

    def test_job_without_tasks_stays_inline_beside_one_with_tasks(self):
        data = {**self.WITH_TASKS}
        data["jobs"] = data["jobs"] + [{"name": "Bare Job", "run_id": "456"}]
        lines = format_alert(data).splitlines()
        self.assertEqual("• Bare Job: https://github.com/example/repo/actions/runs/456", lines[4])
        self.assertEqual(5, len(lines))

    def test_retry_success_with_tasks(self):
        lines = format_alert({**self.WITH_TASKS, "outcome": "retry_success"}).splitlines()
        self.assertEqual(":green_circle: CI passed on retry on `main`", lines[0])
        self.assertEqual("  `:core:x:javaapi:runtime:compileTestKotlin`", lines[2])

    def test_tasks_must_be_an_array(self):
        data = {**BASE, "jobs": [{"name": "A", "run_id": "1", "tasks": ":not:a:list"}]}
        sys.stdin = StringIO(json.dumps(data))
        self.assertEqual(main(), 1)
        sys.stdin = sys.__stdin__

    def test_task_lines_helper_caps_and_counts(self):
        self.assertEqual(["  `:a`", "  `:b`", "  `:c` +1 more"],
                         format_task_lines([":a", ":b", ":c", ":d"]))

    def test_task_lines_helper_on_empty_input(self):
        self.assertEqual([], format_task_lines([]))


class TestJobLinksAndCauses(unittest.TestCase):

    JOB_URL = "https://github.com/example/repo/actions/runs/123/job/456"

    def test_job_id_links_to_the_job(self):
        data = {**BASE, "jobs": [{"name": "Build and Test", "run_id": "123", "job_id": "456"}]}
        self.assertIn(f"({self.JOB_URL})", format_alert(data))

    def test_missing_job_id_links_to_the_run(self):
        self.assertIn(f"({EXPECTED_URL})", format_alert(BASE))

    def test_cause_precedes_the_url_on_a_single_line(self):
        data = {**BASE, "sha": "abc1234567", "outcome": "retry_success",
                "jobs": [{"name": "Build and Test", "run_id": "123", "cause": "HTTP 429 from repo.example.org"}]}
        self.assertEqual(
            ":green_circle: Build and Test passed on retry on `main` — commit `abc1234`"
            f" — HTTP 429 from repo.example.org ({EXPECTED_URL})",
            format_alert(data),
        )

    def test_cause_follows_an_inline_bullet_name(self):
        data = {**BASE, "jobs": [{"name": "A", "run_id": "1", "cause": "DNS lookup failed (x.org)"},
                                 {"name": "B", "run_id": "2"}]}
        lines = format_alert(data).splitlines()
        self.assertEqual("• A — DNS lookup failed (x.org): https://github.com/example/repo/actions/runs/1", lines[1])
        self.assertEqual("• B: https://github.com/example/repo/actions/runs/2", lines[2])

    def test_cause_follows_the_name_above_tasks(self):
        data = {**BASE, "jobs": [{"name": "A", "run_id": "1", "tasks": [":a:test"], "cause": "no known infrastructure cause"}]}
        self.assertEqual("• A — no known infrastructure cause", format_alert(data).splitlines()[1])


class TestAlertLength(unittest.TestCase):

    SUMMARY_URL = "https://github.com/example/repo/actions/runs/999"

    def jobs(self, count, tasks=None):
        return [{"name": f"ci-check / build-and-test / Test (Java {n}) macos-latest", "run_id": "36145963812",
                 "job_id": str(108107150063 + n), "cause": "DNS lookup failed (services.gradle.org)",
                 **({"tasks": tasks} if tasks else {})} for n in range(count)]

    def alert(self, jobs):
        return format_alert({**BASE, "sha": "c7ea22020976", "actor": "viaductbot", "attempt": "2",
                             "outcome": "retry_success", "summary_url": self.SUMMARY_URL, "jobs": jobs})

    def test_twelve_labeled_jobs_fit_the_limit(self):
        self.assertLessEqual(len(self.alert(self.jobs(12))), MAX_ALERT_CHARS)

    def test_jobs_that_do_not_fit_are_counted_and_linked(self):
        lines = self.alert(self.jobs(12)).splitlines()
        self.assertRegex(lines[-1], rf"^\+\d+ more jobs: {self.SUMMARY_URL}$")
        hidden = int(lines[-1].split()[0][1:])
        self.assertEqual(12, len(lines) - 2 + hidden)

    def test_blocks_with_tasks_are_kept_whole(self):
        text = self.alert(self.jobs(12, tasks=[":core:engine:runtime:test", ":core:tenant:api:test", ":a:b:check", ":c:d:e"]))
        self.assertLessEqual(len(text), MAX_ALERT_CHARS)
        lines = text.splitlines()
        self.assertEqual(0, (len(lines) - 2) % 5)

    def test_one_hidden_job_is_singular(self):
        jobs = self.jobs(9)
        full = self.alert(jobs)
        self.assertNotIn("more job", full)
        long_name = {**jobs[0], "name": "x" * (MAX_ALERT_CHARS - len(full) + 20)}
        self.assertTrue(self.alert(jobs + [long_name]).endswith(f"+1 more job: {self.SUMMARY_URL}"))

    def test_without_a_summary_url_the_count_stands_alone(self):
        data = {**BASE, "jobs": self.jobs(12)}
        self.assertRegex(format_alert(data).splitlines()[-1], r"^\+\d+ more jobs?$")

    def test_short_alerts_are_unchanged(self):
        self.assertNotIn("more job", self.alert(self.jobs(2)))


class TestFormatSummary(unittest.TestCase):

    DATA = {**BASE, "sha": "abc1234567", "attempt": "2", "outcome": "retry_success",
            "jobs": [{"name": "Test | macOS", "run_id": "123", "job_id": "456",
                      "tasks": [":a:test", ":b:check"], "cause": "HTTP 429 from repo.example.org"},
                     {"name": "Bare", "run_id": "123"}]}

    def test_heading_names_the_outcome_commit_and_attempt(self):
        self.assertEqual("### CI passed on retry on `main` — commit `abc1234`, attempt 2",
                         format_summary(self.DATA).splitlines()[0])

    def test_row_links_the_job_and_lists_cause_and_tasks(self):
        self.assertIn(
            "| [Test \\| macOS](https://github.com/example/repo/actions/runs/123/job/456)"
            " | HTTP 429 from repo.example.org | `:a:test`, `:b:check` |",
            format_summary(self.DATA),
        )

    def test_missing_cause_and_tasks_render_as_dashes(self):
        self.assertIn("| [Bare](https://github.com/example/repo/actions/runs/123) | — | — |", format_summary(self.DATA))

    def test_main_prints_the_summary_with_the_flag(self):
        sys.stdin = StringIO(json.dumps(self.DATA))
        out = StringIO()
        sys.stdout = out
        try:
            self.assertEqual(main(["--summary"]), 0)
        finally:
            sys.stdout = sys.__stdout__
            sys.stdin = sys.__stdin__
        self.assertTrue(out.getvalue().startswith("### CI passed on retry"))


class TestMainErrorHandling(unittest.TestCase):

    def test_invalid_json(self):
        sys.stdin = StringIO("not json")
        self.assertEqual(main(), 1)

    def test_not_an_object(self):
        sys.stdin = StringIO(json.dumps(["branch", "jobs"]))
        self.assertEqual(main(), 1)

    def test_missing_required_fields(self):
        sys.stdin = StringIO(json.dumps({"branch": "main"}))
        self.assertEqual(main(), 1)

    def test_empty_jobs_array(self):
        data = {**BASE, "jobs": []}
        sys.stdin = StringIO(json.dumps(data))
        self.assertEqual(main(), 1)

    def test_jobs_not_array(self):
        data = {**BASE, "jobs": "Build and Test"}
        sys.stdin = StringIO(json.dumps(data))
        self.assertEqual(main(), 1)

    def test_job_missing_name(self):
        data = {**BASE, "jobs": [{"run_id": "123"}]}
        sys.stdin = StringIO(json.dumps(data))
        self.assertEqual(main(), 1)

    def test_job_missing_run_id(self):
        data = {**BASE, "jobs": [{"name": "Build"}]}
        sys.stdin = StringIO(json.dumps(data))
        self.assertEqual(main(), 1)

    def test_success(self):
        sys.stdin = StringIO(json.dumps(BASE))
        self.assertEqual(main(), 0)

    def tearDown(self):
        sys.stdin = sys.__stdin__


class TestJobsJsonCompactness(unittest.TestCase):
    """Regression tests for the jq -c requirement in ci-manual-trigger.yml.

    The 'Build failed jobs list' step writes jobs_json to $GITHUB_OUTPUT
    using the single-line echo "key=value" format.  If the JSON spans
    multiple lines (i.e., jq is called without -c), the value is silently
    truncated at the first newline.
    """

    def test_single_job_json_is_single_line(self):
        """A single-element jobs array must serialize to one line."""
        jobs = [{"name": "Build and Test", "run_id": "123"}]
        compact = json.dumps(jobs, separators=(",", ":"))
        self.assertEqual(1, len(compact.splitlines()))

    def test_multi_job_json_is_single_line(self):
        """A multi-element jobs array must serialize to one line."""
        jobs = [
            {"name": "Build and Test", "run_id": "111"},
            {"name": "Demo App Tests", "run_id": "222"},
            {"name": "API Compatibility", "run_id": "333"},
        ]
        compact = json.dumps(jobs, separators=(",", ":"))
        self.assertEqual(1, len(compact.splitlines()))

    def test_compact_json_round_trips_through_format_alert(self):
        """Compact JSON fed to format_alert must produce valid output."""
        jobs = [
            {"name": "Build and Test", "run_id": "111"},
            {"name": "Demo App Tests", "run_id": "222"},
        ]
        data = {
            "branch": "main",
            "server_url": "https://github.com",
            "repository": "example/repo",
            "jobs": jobs,
        }
        result = format_alert(data)
        self.assertIn("Build and Test", result)
        self.assertIn("Demo App Tests", result)


if __name__ == "__main__":
    unittest.main()
