import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

from post_build_scan_comments import (
    COMMENT_MARKER,
    build_comment_body,
    build_comment_payload,
    collect_build_scan_data,
)


class TestPostBuildScanComments(unittest.TestCase):
    def test_collect_build_scan_data_reads_single_json_payloads(self):
        with tempfile.TemporaryDirectory() as tempdir:
            artifact_dir = Path(tempdir) / "build-scan-artifact-test-21-ubuntu-latest"
            artifact_dir.mkdir(parents=True)
            (artifact_dir / "build-scan-artifact.json").write_text(
                '{"pr_number": 42, "label": "Test (Java 21, ubuntu-latest)", "url": "https://scans.gradle.com/s/abc123"}'
            )

            pr_number, scans_by_job = collect_build_scan_data(Path(tempdir))

            self.assertEqual(pr_number, 42)
            self.assertEqual(
                scans_by_job["Test (Java 21, ubuntu-latest)"],
                "https://scans.gradle.com/s/abc123",
            )

    def test_collect_build_scan_data_handles_a_single_flat_downloaded_artifact(self):
        with tempfile.TemporaryDirectory() as tempdir:
            tempdir_path = Path(tempdir)
            (tempdir_path / "build-scan-artifact.json").write_text(
                '{"pr_number": 7, "label": "Build assemble (Java 11, ubuntu-latest)", "url": "https://gradle.com/s/flat123"}'
            )

            pr_number, scans_by_job = collect_build_scan_data(tempdir_path)

            self.assertEqual(pr_number, 7)
            self.assertEqual(
                scans_by_job["Build assemble (Java 11, ubuntu-latest)"],
                "https://gradle.com/s/flat123",
            )

    def test_collect_build_scan_data_skips_malformed_metadata_files(self):
        with tempfile.TemporaryDirectory() as tempdir:
            artifact_dir = Path(tempdir) / "build-scan-artifact-test-21-ubuntu-latest"
            artifact_dir.mkdir(parents=True)
            (artifact_dir / "broken.json").write_text("{not json")
            (artifact_dir / "build-scan-artifact.json").write_text(
                '{"pr_number": 42, "label": "Test (Java 21, ubuntu-latest)", "url": "https://scans.gradle.com/s/valid123"}'
            )

            _, scans_by_job = collect_build_scan_data(Path(tempdir))

            self.assertEqual(len(scans_by_job), 1)
            self.assertEqual(
                scans_by_job["Test (Java 21, ubuntu-latest)"],
                "https://scans.gradle.com/s/valid123",
            )

    def test_build_comment_body_includes_marker_and_sorts_rows_by_label(self):
        body = build_comment_body(
            run_url="https://github.com/example/repo/actions/runs/123",
            scans_by_job={
                "Zed": "https://gradle.com/s/zed",
                "Alpha": "https://gradle.com/s/alpha",
            },
        )

        self.assertIn(COMMENT_MARKER, body)
        self.assertLess(
            body.index("| Alpha | [View Build Scan](https://gradle.com/s/alpha) |"),
            body.index("| Zed | [View Build Scan](https://gradle.com/s/zed) |"),
        )

    def test_build_comment_payload_renders_comment_body(self):
        with tempfile.TemporaryDirectory() as tempdir:
            artifact_dir = Path(tempdir) / "build-scan-artifact-test-21-ubuntu-latest"
            artifact_dir.mkdir(parents=True)
            (artifact_dir / "build-scan-artifact.json").write_text(
                '{"pr_number": 42, "label": "Test (Java 21, ubuntu-latest)", "url": "https://scans.gradle.com/s/abc123"}'
            )

            payload = build_comment_payload(
                base_dir=Path(tempdir),
                run_id="123",
                repository="example/repo",
                server_url="https://github.com",
            )

        self.assertEqual(payload["pr_number"], 42)
        self.assertEqual(payload["comment_marker"], COMMENT_MARKER)
        self.assertIn(COMMENT_MARKER, payload["body"])
        self.assertIn("https://github.com/example/repo/actions/runs/123", payload["body"])

    def test_build_comment_payload_returns_empty_body_when_pr_number_is_missing(self):
        with tempfile.TemporaryDirectory() as tempdir:
            artifact_dir = Path(tempdir) / "build-scan-artifact-test-21-ubuntu-latest"
            artifact_dir.mkdir(parents=True)
            (artifact_dir / "build-scan-artifact.json").write_text(
                '{"pr_number": null, "label": "Test (Java 21, ubuntu-latest)", "url": "https://scans.gradle.com/s/abc123"}'
            )

            payload = build_comment_payload(
                base_dir=Path(tempdir),
                run_id="123",
                repository="example/repo",
                server_url="https://github.com",
            )

        self.assertIsNone(payload["pr_number"])
        self.assertIsNone(payload["body"])
        self.assertEqual(payload["comment_marker"], COMMENT_MARKER)

    def test_build_comment_payload_returns_empty_body_when_scans_are_missing(self):
        with tempfile.TemporaryDirectory() as tempdir:
            artifact_dir = Path(tempdir) / "build-scan-artifact-test-21-ubuntu-latest"
            artifact_dir.mkdir(parents=True)
            (artifact_dir / "build-scan-artifact.json").write_text('{"pr_number": 42, "label": "", "url": ""}')

            payload = build_comment_payload(
                base_dir=Path(tempdir),
                run_id="123",
                repository="example/repo",
                server_url="https://github.com",
            )

        self.assertIsNone(payload["pr_number"])
        self.assertIsNone(payload["body"])
        self.assertEqual(payload["comment_marker"], COMMENT_MARKER)
