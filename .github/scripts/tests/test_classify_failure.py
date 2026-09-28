import sys
import unittest
from io import BytesIO, StringIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from classify_failure import UNRECOGNIZED, classify_failure, main


class TestClassifyFailure(unittest.TestCase):

    def test_repository_429_names_the_host(self):
        log = ("> Could not GET 'https://repo.maven.apache.org/maven2/com/google/guava/guava/31.0.1-jre/"
               "guava-31.0.1-jre.pom'. Received status code 429 from server: Too Many Requests\n")
        self.assertEqual("HTTP 429 from repo.maven.apache.org", classify_failure(log))

    def test_repository_403_names_the_host(self):
        log = ("> Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/"
               "annotations-13.0.jar'. Received status code 403 from server: Forbidden\n")
        self.assertEqual("HTTP 403 from repo.maven.apache.org", classify_failure(log))

    def test_artifact_download(self):
        log = ('##[error]Unable to download artifact(s): Failed to ListArtifacts: Received non-retryable error: '
               'Failed request: (403) Forbidden: Error from intermediary with HTTP status code 403 "Forbidden"\n')
        self.assertEqual("GitHub artifact download failed", classify_failure(log))

    def test_dns_names_the_host(self):
        log = '##[error]Exception in thread "main" java.net.UnknownHostException: services.gradle.org\n'
        self.assertEqual("DNS lookup failed (services.gradle.org)", classify_failure(log))

    def test_test_executor_never_connected(self):
        log = "> Unable to connect to the child process 'Gradle Test Executor 4'.\n"
        self.assertEqual("Gradle child process never connected (Gradle Test Executor)", classify_failure(log))

    def test_worker_daemon_never_connected(self):
        log = "> Unable to connect to the child process 'Gradle Worker Daemon 1'.\n"
        self.assertEqual("Gradle child process never connected (Gradle Worker Daemon)", classify_failure(log))

    def test_test_failure_is_unrecognized(self):
        log = "> Task :core:shared:utils:test FAILED\nFooTest > bar FAILED\n"
        self.assertEqual(UNRECOGNIZED, classify_failure(log))

    def test_empty_log_is_unrecognized(self):
        self.assertEqual(UNRECOGNIZED, classify_failure(""))

    def test_ansi_and_crlf_are_ignored(self):
        log = "\x1b[31mjava.net.UnknownHostException: repo.example.org\x1b[0m\r\n"
        self.assertEqual("DNS lookup failed (repo.example.org)", classify_failure(log))

    def test_repository_status_omits_url_credentials(self):
        log = ("> Could not GET 'https://user:token@mirror.example.com/x.pom'. "
               "Received status code 429 from server\n")
        self.assertEqual("HTTP 429 from mirror.example.com", classify_failure(log))

    def test_repository_status_wins_over_an_earlier_signature(self):
        log = ("java.net.UnknownHostException: services.gradle.org\n"
               "> Could not GET 'https://repo.maven.apache.org/x.pom'. Received status code 502 from server\n")
        self.assertEqual("HTTP 502 from repo.maven.apache.org", classify_failure(log))

    def test_repository_status_wins_over_a_later_signature(self):
        log = ("> Could not GET 'https://plugins.gradle.org/m2/x.pom'. Received status code 502 from server\n"
               "> Unable to connect to the child process 'Gradle Test Executor 1'.\n")
        self.assertEqual("HTTP 502 from plugins.gradle.org", classify_failure(log))

    def test_main_reads_bytes_and_prints_the_label(self):
        stdin = type("Stdin", (), {"buffer": BytesIO(b"\xffjava.net.UnknownHostException: a.org\n")})()
        out = StringIO()
        sys.stdin, sys.stdout = stdin, out
        try:
            self.assertEqual(0, main())
        finally:
            sys.stdin, sys.stdout = sys.__stdin__, sys.__stdout__
        self.assertEqual("DNS lookup failed (a.org)\n", out.getvalue())


if __name__ == "__main__":
    unittest.main()
