import contextlib
import http.client
import io
import os
import shutil
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent.parent))

from probe_published_artifacts import (
    Coordinate,
    find_coordinate_files,
    find_problems,
    http_status,
    main,
    parse_coordinates,
    pom_url,
    probe,
    read_coordinates,
)

CENTRAL = "central com.airbnb.viaduct:api:2.0.0"
PORTAL = (
    "portal com.airbnb.viaduct.application-gradle-plugin:"
    "com.airbnb.viaduct.application-gradle-plugin.gradle.plugin:2.0.0"
)


def write_coordinate_file(root, project, *lines):
    directory = os.path.join(root, project, "build/reports/publication")
    os.makedirs(directory)
    path = os.path.join(directory, "coordinates.txt")
    with open(path, "w") as handle:
        handle.write("\n".join(lines) + "\n")
    return path


class TestParseCoordinates(unittest.TestCase):

    def test_parses_central(self):
        self.assertEqual(
            parse_coordinates(CENTRAL),
            [Coordinate("central", "com.airbnb.viaduct", "api", "2.0.0")],
        )

    def test_parses_portal_marker(self):
        marker = parse_coordinates(PORTAL)[0]
        self.assertEqual(marker.repository, "portal")
        self.assertEqual(marker.group, "com.airbnb.viaduct.application-gradle-plugin")
        self.assertTrue(marker.artifact.endswith(".gradle.plugin"))

    def test_skips_blank_lines(self):
        self.assertEqual(len(parse_coordinates(f"\n{CENTRAL}\n\n")), 1)

    def test_rejects_unknown_repository(self):
        with self.assertRaises(ValueError) as caught:
            parse_coordinates("jcenter com.airbnb.viaduct:api:2.0.0")
        self.assertIn("unknown repository", str(caught.exception))

    def test_rejects_missing_version(self):
        with self.assertRaises(ValueError) as caught:
            parse_coordinates("central com.airbnb.viaduct:api")
        self.assertIn("<group>:<artifact>:<version>", str(caught.exception))

    def test_rejects_empty_gav_segment(self):
        with self.assertRaises(ValueError):
            parse_coordinates("central com.airbnb.viaduct::2.0.0")

    def test_rejects_extra_fields(self):
        with self.assertRaises(ValueError):
            parse_coordinates("central com.airbnb.viaduct:api:2.0.0 extra")


class TestPomUrl(unittest.TestCase):

    def test_central_url(self):
        coordinate = Coordinate("central", "com.airbnb.viaduct.service", "api", "2.0.0")
        self.assertEqual(
            pom_url(coordinate),
            "https://repo.maven.apache.org/maven2/com/airbnb/viaduct/service/api/"
            "2.0.0/api-2.0.0.pom",
        )

    def test_portal_url(self):
        self.assertEqual(
            pom_url(parse_coordinates(PORTAL)[0]),
            "https://plugins.gradle.org/m2/com/airbnb/viaduct/"
            "application-gradle-plugin/"
            "com.airbnb.viaduct.application-gradle-plugin.gradle.plugin/2.0.0/"
            "com.airbnb.viaduct.application-gradle-plugin.gradle.plugin-2.0.0.pom",
        )


class TestHttpStatus(unittest.TestCase):

    def status_when_urlopen_raises(self, error):
        with mock.patch("urllib.request.urlopen", side_effect=error):
            return http_status("https://example.invalid/a.pom")

    def test_returns_the_code_for_an_http_error(self):
        error = urllib.error.HTTPError(
            "https://example.invalid/a.pom", 429, "Too Many Requests", {}, None
        )
        self.assertEqual(self.status_when_urlopen_raises(error), 429)

    def test_returns_zero_for_a_connection_failure(self):
        error = urllib.error.URLError("connection refused")
        self.assertEqual(self.status_when_urlopen_raises(error), 0)

    def test_returns_zero_for_a_truncated_response(self):
        # IncompleteRead is not an OSError, so it needs its own except clause.
        error = http.client.IncompleteRead(b"partial")
        self.assertEqual(self.status_when_urlopen_raises(error), 0)


class TempDirTestCase(unittest.TestCase):

    def setUp(self):
        self.tmpdir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmpdir)


class TestFindCoordinateFiles(TempDirTestCase):

    def test_finds_files_across_nested_projects(self):
        write_coordinate_file(self.tmpdir, "publications/api", CENTRAL)
        write_coordinate_file(self.tmpdir, "core/x/javaapi/registry-apt", CENTRAL)
        found = find_coordinate_files(self.tmpdir)
        self.assertEqual(len(found), 2)

    def test_ignores_coordinates_outside_the_report_dir(self):
        stray = os.path.join(self.tmpdir, "build")
        os.makedirs(stray)
        with open(os.path.join(stray, "coordinates.txt"), "w") as handle:
            handle.write(CENTRAL + "\n")
        self.assertEqual(find_coordinate_files(self.tmpdir), [])

    def test_returns_empty_when_nothing_was_written(self):
        self.assertEqual(find_coordinate_files(self.tmpdir), [])


class TestReadCoordinates(TempDirTestCase):

    def test_collects_across_files(self):
        write_coordinate_file(self.tmpdir, "publications/api", CENTRAL)
        write_coordinate_file(self.tmpdir, "gradle-plugins/application", PORTAL)
        coordinates = read_coordinates(find_coordinate_files(self.tmpdir))
        self.assertEqual({c.repository for c in coordinates}, {"central", "portal"})

    def test_drops_duplicates(self):
        write_coordinate_file(self.tmpdir, "one", CENTRAL)
        write_coordinate_file(self.tmpdir, "two", CENTRAL)
        self.assertEqual(len(read_coordinates(find_coordinate_files(self.tmpdir))), 1)

    def test_reads_multiple_lines_from_one_file(self):
        write_coordinate_file(self.tmpdir, "gradle-plugins/application", CENTRAL, PORTAL)
        self.assertEqual(len(read_coordinates(find_coordinate_files(self.tmpdir))), 2)


class TestFindProblems(unittest.TestCase):

    def all_coordinates(self):
        return parse_coordinates(f"{CENTRAL}\n{PORTAL}")

    def test_no_problems_when_both_repositories_present(self):
        self.assertEqual(find_problems(self.all_coordinates(), "2.0.0"), [])

    def test_empty_list_is_a_problem(self):
        problems = find_problems([], "2.0.0")
        self.assertEqual(len(problems), 1)
        self.assertIn("produced nothing", problems[0])

    def test_missing_portal_is_a_problem(self):
        problems = find_problems(parse_coordinates(CENTRAL), "2.0.0")
        self.assertIn("no 'portal' coordinates found", problems)

    def test_missing_central_is_a_problem(self):
        problems = find_problems(parse_coordinates(PORTAL), "2.0.0")
        self.assertIn("no 'central' coordinates found", problems)

    def test_version_mismatch_is_a_problem(self):
        problems = find_problems(self.all_coordinates(), "2.1.0")
        self.assertEqual(len(problems), 1)
        self.assertIn("expected 2.1.0", problems[0])


class FakeClock:
    """A clock that only advances when the code under test sleeps."""

    def __init__(self):
        self.elapsed = 0.0
        self.slept = []

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.elapsed += seconds


class TestProbe(unittest.TestCase):

    def setUp(self):
        self.clock = FakeClock()

    def run_probe(self, urls, fetch, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            return probe(
                urls,
                fetch=fetch,
                now=lambda: self.clock.elapsed,
                sleep=self.clock.sleep,
                **kwargs,
            )

    def test_returns_empty_when_all_visible(self):
        self.assertEqual(self.run_probe(["a", "b"], lambda url: 200), [])
        self.assertEqual(self.clock.slept, [])

    def test_reports_every_missing_url_not_just_the_first(self):
        missing = self.run_probe(
            ["a", "b", "c"],
            lambda url: 200 if url == "b" else 404,
            timeout_seconds=0,
        )
        self.assertEqual([url for url, _ in missing], ["a", "c"])

    def test_reports_the_last_status_seen(self):
        missing = self.run_probe(["a", "b"], {"a": 429, "b": 503}.get, timeout_seconds=0)
        self.assertEqual(missing, [("a", 429), ("b", 503)])

    def test_reports_zero_when_the_request_never_reached_a_server(self):
        missing = self.run_probe(["a"], lambda url: 0, timeout_seconds=0)
        self.assertEqual(missing, [("a", 0)])

    def test_stops_polling_a_url_once_it_appears(self):
        seen = []
        failures = {"b": 1}

        def fetch(url):
            seen.append(url)
            if failures.get(url, 0) > 0:
                failures[url] -= 1
                return 404
            return 200

        self.assertEqual(
            self.run_probe(["a", "b"], fetch, timeout_seconds=100), []
        )
        self.assertEqual(seen, ["a", "b", "b"])

    def test_deadline_is_global_not_per_url(self):
        urls = [f"url-{number}" for number in range(20)]
        missing = self.run_probe(
            urls, lambda url: 404, timeout_seconds=60, sleep_seconds=30
        )
        self.assertEqual([url for url, _ in missing], urls)
        self.assertLessEqual(self.clock.elapsed, 60)

    def test_final_sleep_does_not_overshoot_the_deadline(self):
        self.run_probe(
            ["a"], lambda url: 404, timeout_seconds=10, sleep_seconds=30
        )
        self.assertEqual(self.clock.slept, [10])


class TestMain(TempDirTestCase):
    """Covers the exit codes and ::error:: output that release.yml depends on."""

    def run_main(self, version, fetch=lambda url: 200):
        with contextlib.redirect_stdout(io.StringIO()) as captured:
            code = main(
                ["--version", version, "--root", self.tmpdir, "--timeout-seconds", "0"],
                fetch=fetch,
            )
        return code, captured.getvalue()

    def test_succeeds_when_every_artifact_is_visible(self):
        write_coordinate_file(self.tmpdir, "publications/api", CENTRAL, PORTAL)
        code, output = self.run_main("2.0.0")
        self.assertEqual(code, 0)
        self.assertIn("all 2 artifacts are visible", output)

    def test_fails_when_nothing_was_derived(self):
        code, output = self.run_main("2.0.0")
        self.assertEqual(code, 1)
        self.assertIn("::error::no coordinates found", output)

    def test_fails_on_a_malformed_coordinate_file(self):
        write_coordinate_file(self.tmpdir, "publications/api", "central nonsense")
        code, output = self.run_main("2.0.0")
        self.assertEqual(code, 1)
        self.assertIn("::error::malformed coordinate file", output)

    def test_fails_on_a_version_mismatch(self):
        write_coordinate_file(self.tmpdir, "publications/api", CENTRAL, PORTAL)
        code, output = self.run_main("2.1.0")
        self.assertEqual(code, 1)
        self.assertIn("expected 2.1.0", output)

    def test_fails_naming_the_missing_artifact_and_its_status(self):
        write_coordinate_file(self.tmpdir, "publications/api", CENTRAL, PORTAL)
        code, output = self.run_main(
            "2.0.0", fetch=lambda url: 200 if "plugins.gradle.org" in url else 404
        )
        self.assertEqual(code, 1)
        self.assertIn("::error::not visible", output)
        self.assertIn("last status HTTP 404", output)
        self.assertIn("/com/airbnb/viaduct/api/2.0.0/api-2.0.0.pom", output)


if __name__ == "__main__":
    unittest.main()
