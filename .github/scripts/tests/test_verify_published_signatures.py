import contextlib
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

from verify_published_signatures import (
    EXPIRY_WARNING_DAYS,
    SECONDS_PER_DAY,
    FETCH_ATTEMPTS,
    expiry_warnings,
    fetch,
    import_signing_keys,
    main,
    signature_problem,
    snapshot_files,
)

FIXTURES = Path(__file__).parent / "fixtures" / "signatures"
KEY = str(FIXTURES / "release-key.asc")
KEY_FINGERPRINT = "75F166DF3465DE1E1588CBD5477DFC91083C3627"
KEY_EXPIRY = 4070962800
PAYLOAD = (FIXTURES / "payload.txt").read_bytes()
SIGNATURE = (FIXTURES / "payload.txt.asc").read_bytes()
OTHER_KEY_SIGNATURE = (FIXTURES / "payload.txt.other.asc").read_bytes()
EXPIRED_KEY = str(FIXTURES / "expired-key.asc")
EXPIRED_KEY_SIGNATURE = (FIXTURES / "payload.txt.expired.asc").read_bytes()
SUBKEY_KEY = str(FIXTURES / "signing-subkey-key.asc")
SIGNING_SUBKEY_FINGERPRINT = "32CE530C5A22C3C0D8894C040BE76E103634FD67"
SIGNING_SUBKEY_EXPIRY = 4039426800

VERSION = "4.0.0-rc.1-SNAPSHOT"
STAMP = "20261111.120000"
BUILT_AFTER = "20261111.115900"
OLDER_STAMP = "20261110.061953"
TIMESTAMP = f"4.0.0-rc.1-{STAMP}-1"
BASE = "https://central.sonatype.com/repository/maven-snapshots"
API_DIR = f"{BASE}/com/airbnb/viaduct/api/{VERSION}"
MARKER = "com.airbnb.viaduct.module-gradle-plugin.gradle.plugin"
MARKER_DIR = f"{BASE}/com/airbnb/viaduct/module-gradle-plugin/{MARKER}/{VERSION}"


def metadata(*entries, stamp=STAMP, value=None):
    versions = "".join(
        "<snapshotVersion>"
        + (f"<classifier>{classifier}</classifier>" if classifier else "")
        + f"<extension>{extension}</extension><value>{value or f'4.0.0-rc.1-{stamp}-1'}</value>"
        "</snapshotVersion>"
        for classifier, extension in entries
    )
    snapshot = f"<snapshot><timestamp>{stamp}</timestamp><buildNumber>1</buildNumber></snapshot>" if stamp else ""
    return (
        f"<metadata><versioning>{snapshot}<snapshotVersions>"
        f"{versions}</snapshotVersions></versioning></metadata>"
    ).encode()


class FakeClock:
    seconds = 0

    def now(self):
        return self.seconds

    def sleep(self, seconds):
        self.seconds += seconds


def signed(directory, name, signature=SIGNATURE):
    return {f"{directory}/{name}": PAYLOAD, f"{directory}/{name}.asc": signature}


def published_rc():
    api_jar = f"api-{TIMESTAMP}.jar"
    marker_pom = f"{MARKER}-{TIMESTAMP}.pom"
    return {
        f"{API_DIR}/maven-metadata.xml": metadata((None, "jar"), (None, "jar.asc")),
        **signed(API_DIR, api_jar),
        f"{MARKER_DIR}/maven-metadata.xml": metadata((None, "pom"), (None, "pom.asc")),
        **signed(MARKER_DIR, marker_pom),
    }


@unittest.skipUnless(shutil.which("gpg"), "gpg is not installed")
class TestSignatures(unittest.TestCase):

    def setUp(self):
        self.gnupg_home = tempfile.mkdtemp()
        os.chmod(self.gnupg_home, 0o700)
        self.addCleanup(shutil.rmtree, self.gnupg_home, ignore_errors=True)
        self.keys = import_signing_keys(self.gnupg_home, KEY)
        self.work_dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.work_dir)

    def problem_for(self, payload, signature):
        data_path = os.path.join(self.work_dir, "payload")
        Path(data_path).write_bytes(payload)
        Path(f"{data_path}.asc").write_bytes(signature)
        return signature_problem(self.gnupg_home, data_path, f"{data_path}.asc")

    def test_import_reports_each_signing_key_and_its_expiry(self):
        self.assertEqual(self.keys, [(KEY_FINGERPRINT, KEY_EXPIRY)])
        self.assertEqual(
            import_signing_keys(self.gnupg_home, SUBKEY_KEY),
            [(KEY_FINGERPRINT, KEY_EXPIRY), (SIGNING_SUBKEY_FINGERPRINT, SIGNING_SUBKEY_EXPIRY)],
        )

    def test_signature_outcomes(self):
        import_signing_keys(self.gnupg_home, EXPIRED_KEY)
        cases = [
            ("good", PAYLOAD, SIGNATURE, None),
            ("tampered file", PAYLOAD + b"x", SIGNATURE, "BADSIG"),
            ("signed by another key", PAYLOAD, OTHER_KEY_SIGNATURE, "NO_PUBKEY"),
            ("signed by an expired key", PAYLOAD, EXPIRED_KEY_SIGNATURE, "EXPKEYSIG"),
        ]
        for name, payload, signature, expected in cases:
            with self.subTest(name):
                self.assertEqual(self.problem_for(payload, signature), expected)


class TestSnapshotFiles(unittest.TestCase):

    def test_lists_payloads_with_classifiers_and_skips_signatures(self):
        xml = metadata((None, "jar"), (None, "jar.asc"), ("sources", "jar"), (None, "pom"))
        self.assertEqual(
            snapshot_files("api", xml),
            (STAMP, [f"api-{TIMESTAMP}.jar", f"api-{TIMESTAMP}-sources.jar", f"api-{TIMESTAMP}.pom"]),
        )


class TestExpiryWarnings(unittest.TestCase):

    def test_warns_only_inside_the_window(self):
        expires = 1_800_000_000
        window = EXPIRY_WARNING_DAYS * SECONDS_PER_DAY
        keys = [("KEY", expires), ("NEVER", None)]
        self.assertEqual(
            expiry_warnings(keys, expires - window + 1),
            ["release key KEY expires on 2027-01-15"],
        )
        self.assertEqual(expiry_warnings(keys, expires - window), [])


@unittest.skipUnless(shutil.which("gpg"), "gpg is not installed")
class TestMain(unittest.TestCase):

    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)
        directory = os.path.join(self.root, "build/reports/publication")
        os.makedirs(directory)
        Path(directory, "coordinates.txt").write_text(
            f"central com.airbnb.viaduct:api:{VERSION}\n"
            f"portal com.airbnb.viaduct.module-gradle-plugin:{MARKER}:{VERSION}\n"
        )
        self.clock = FakeClock()

    def run_main(self, files, version=VERSION, built_after=BUILT_AFTER, timeout_seconds="600"):
        def fetch(url):
            body = files.get(url)
            return (body.pop(0) if len(body) > 1 else body[0]) if isinstance(body, list) else body
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            arguments = ["--root", self.root, "--version", version, "--built-after", built_after]
            code = main(
                arguments + ["--timeout-seconds", timeout_seconds, "--key", KEY],
                fetch=fetch,
                now=self.clock.now,
                sleep=self.clock.sleep,
            )
        return code, output.getvalue()

    def test_passes_when_every_file_is_signed(self):
        code, output = self.run_main(published_rc())
        self.assertEqual(code, 0, output)
        self.assertIn(f"all 2 files of 2 coordinates are signed by {KEY_FINGERPRINT}", output)

    def test_waits_until_the_metadata_lists_this_runs_build(self):
        files = published_rc()
        marker_metadata = f"{MARKER_DIR}/maven-metadata.xml"
        files[marker_metadata] = [metadata((None, "pom"), stamp=OLDER_STAMP), files[marker_metadata]]
        code, output = self.run_main(files)
        self.assertEqual(code, 0, output)
        self.assertGreater(self.clock.seconds, 0)

    def test_coordinates_share_one_deadline(self):
        files = published_rc()
        files[f"{API_DIR}/maven-metadata.xml"] = metadata((None, "jar"), stamp=OLDER_STAMP)
        files[f"{MARKER_DIR}/maven-metadata.xml"] = metadata((None, "pom"), stamp=OLDER_STAMP)
        code, output = self.run_main(files, timeout_seconds="45")
        self.assertEqual(code, 1, output)
        self.assertEqual(output.count("older than"), 2)
        self.assertEqual(self.clock.seconds, 45)

    def test_fails_on_each_publication_defect(self):
        api_jar = f"{API_DIR}/api-{TIMESTAMP}.jar"
        marker_metadata = f"{MARKER_DIR}/maven-metadata.xml"
        older_entry = metadata((None, "pom"), value=f"4.0.0-rc.1-{OLDER_STAMP}-18")
        cases = [
            ("missing signature", f"{api_jar}.asc", None, f"missing signature: {api_jar}.asc"),
            ("no metadata", marker_metadata, None, "no snapshot metadata"),
            ("only an older build", marker_metadata, metadata((None, "pom"), stamp=OLDER_STAMP), "older than"),
            ("a file from an older build", marker_metadata, older_entry, "not the current build"),
            ("no build stamp", marker_metadata, metadata((None, "pom"), stamp=None), "<snapshot> timestamp"),
            ("a malformed build stamp", marker_metadata, metadata((None, "pom"), stamp="garbage"), "<snapshot> timestamp"),
            ("an entry without a value", marker_metadata, metadata((None, "pom")).replace(TIMESTAMP.encode(), b""), "not the current build"),
            ("metadata lists no files", marker_metadata, metadata(), "lists no files"),
            ("metadata is not XML", marker_metadata, b"<html>", "error checking"),
        ]
        for name, url, body, expected in cases:
            with self.subTest(name):
                files = published_rc()
                files[url] = body
                self.clock = FakeClock()
                code, output = self.run_main(files)
                self.assertEqual(code, 1, output)
                self.assertIn(expected, output)

    def test_rejects_bad_arguments(self):
        cases = [
            ({"version": "4.0.0"}, "is not a -SNAPSHOT version"),
            ({"built_after": "2026-11-11T12:00"}, "is not yyyyMMdd.HHmmss"),
        ]
        for arguments, expected in cases:
            with self.subTest(expected):
                code, output = self.run_main(published_rc(), **arguments)
                self.assertEqual(code, 1)
                self.assertIn(expected, output)


class TestFetch(unittest.TestCase):

    def response(self, body):
        return mock.MagicMock(**{"__enter__.return_value.read.return_value": body})

    def http_error(self, code):
        return urllib.error.HTTPError("https://example.invalid", code, "error", {}, None)

    def test_retries_server_errors_but_not_missing_or_forbidden(self):
        sleep = mock.Mock()
        with mock.patch("urllib.request.urlopen", side_effect=[self.http_error(503), self.response(b"ok")]):
            self.assertEqual(fetch("https://example.invalid", sleep=sleep), b"ok")
        with mock.patch("urllib.request.urlopen", side_effect=[self.http_error(404)]):
            self.assertIsNone(fetch("https://example.invalid", sleep=sleep))
        with mock.patch("urllib.request.urlopen", side_effect=[self.http_error(403)]):
            with self.assertRaises(urllib.error.HTTPError):
                fetch("https://example.invalid", sleep=sleep)
        with mock.patch("urllib.request.urlopen", side_effect=[self.http_error(503)] * FETCH_ATTEMPTS):
            with self.assertRaises(urllib.error.HTTPError):
                fetch("https://example.invalid", sleep=sleep)
        self.assertEqual(sleep.call_count, 1 + (FETCH_ATTEMPTS - 1))


if __name__ == "__main__":
    unittest.main()
