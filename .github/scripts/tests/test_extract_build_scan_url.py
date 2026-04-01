import sys
import tempfile
import unittest
from pathlib import Path
from subprocess import run

sys.path.insert(0, str(Path(__file__).parent.parent))

from extract_build_scan_url import extract_build_scan_url


class TestExtractBuildScanUrl(unittest.TestCase):
    def test_returns_none_when_no_url_present(self):
        self.assertIsNone(extract_build_scan_url("plain gradle output"))

    def test_extracts_gradle_com_url(self):
        text = "Publishing build scan...\nhttps://gradle.com/s/abc123def\n"
        self.assertEqual(extract_build_scan_url(text), "https://gradle.com/s/abc123def")

    def test_extracts_scans_gradle_com_url(self):
        text = "Publishing build scan...\nhttps://scans.gradle.com/s/xyz789\n"
        self.assertEqual(extract_build_scan_url(text), "https://scans.gradle.com/s/xyz789")

    def test_returns_last_url_when_multiple_are_present(self):
        text = "\n".join([
            "Earlier: https://gradle.com/s/first123",
            "Later: https://scans.gradle.com/s/second456",
        ])
        self.assertEqual(extract_build_scan_url(text), "https://scans.gradle.com/s/second456")

    def test_cli_reads_file_and_prints_url(self):
        with tempfile.NamedTemporaryFile(mode="w", suffix=".log", delete=False) as f:
            f.write("Scan:\nhttps://scans.gradle.com/s/abc123xyz\n")
            log_path = Path(f.name)

        result = run(
            [sys.executable, str(Path(__file__).parent.parent / "extract_build_scan_url.py"), str(log_path)],
            capture_output=True,
            text=True,
            check=True,
        )

        self.assertEqual(result.stdout.strip(), "https://scans.gradle.com/s/abc123xyz")


if __name__ == "__main__":
    unittest.main()
