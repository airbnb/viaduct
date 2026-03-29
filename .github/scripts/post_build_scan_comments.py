#!/usr/bin/env python3
"""Collect build scan artifacts and render a PR comment payload."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


COMMENT_MARKER = "<!-- viaduct-build-scan-comment -->"


def read_metadata_scan_entry(file_path: Path) -> tuple[str, str] | None:
    try:
        metadata = json.loads(file_path.read_text())
    except json.JSONDecodeError:
        return None

    label = metadata.get("label")
    url = metadata.get("url")
    if not label or not url:
        return None
    return label, url


def read_legacy_scan_entry(relative_dir: str, file_path: Path) -> tuple[str, str] | None:
    if file_path.name == "pr-number.txt" or file_path.suffix != ".txt":
        return None

    url = file_path.read_text().strip()
    if not url or url == "null":
        return None

    job_name = file_path.stem
    matrix_info = relative_dir.removeprefix("build-scan-urls-").replace(f"{job_name}-", "", 1)
    return f"{job_name} ({matrix_info})", url


def collect_build_scan_data(base_dir: Path) -> tuple[int | None, dict[str, str]]:
    if not base_dir.exists():
        return None, {}

    pr_number = None
    scans_by_job: dict[str, str] = {}
    artifact_dirs = sorted(entry for entry in base_dir.iterdir() if entry.is_dir())
    candidate_dirs = artifact_dirs or [base_dir]

    for dir_path in candidate_dirs:
        if pr_number is None:
            pr_file = dir_path / "pr-number.txt"
            if pr_file.exists():
                raw_pr_number = pr_file.read_text().strip()
                if raw_pr_number and raw_pr_number != "null":
                    try:
                        pr_number = int(raw_pr_number)
                    except ValueError:
                        pass

        files = sorted(entry for entry in dir_path.iterdir() if entry.is_file())
        metadata_files = [entry for entry in files if entry.suffix == ".json"]
        if metadata_files:
            for file_path in metadata_files:
                metadata_entry = read_metadata_scan_entry(file_path)
                if metadata_entry is None:
                    continue
                label, url = metadata_entry
                scans_by_job[label] = url
            continue

        relative_dir = "." if dir_path == base_dir else str(dir_path.relative_to(base_dir))
        for file_path in files:
            legacy_entry = read_legacy_scan_entry(relative_dir, file_path)
            if legacy_entry is None:
                continue
            label, url = legacy_entry
            scans_by_job[label] = url

    return pr_number, scans_by_job


def build_comment_body(*, run_url: str, scans_by_job: dict[str, str]) -> str:
    body_lines = [
        COMMENT_MARKER,
        "## Gradle Build Scan URLs",
        "",
        f"The [Build and Test workflow]({run_url}) produced Gradle build scans. Here are the build scan links for debugging:",
        "",
        "| Job | Build Scan |",
        "|-----|------------|",
    ]
    for job, url in sorted(scans_by_job.items()):
        body_lines.append(f"| {job} | [View Build Scan]({url}) |")
    body_lines.extend([
        "",
        "---",
        "*Posted automatically by the post-build-scan-comments workflow.*",
    ])
    return "\n".join(body_lines)


def build_comment_payload(
    *,
    base_dir: Path,
    run_id: str,
    repository: str,
    server_url: str,
) -> dict[str, int | str | None]:
    pr_number, scans_by_job = collect_build_scan_data(base_dir)
    if pr_number is None:
        return {
            "pr_number": None,
            "body": None,
            "comment_marker": COMMENT_MARKER,
        }
    if not scans_by_job:
        return {
            "pr_number": pr_number,
            "body": None,
            "comment_marker": COMMENT_MARKER,
        }

    run_url = f"{server_url.rstrip('/')}/{repository}/actions/runs/{run_id}"
    return {
        "pr_number": pr_number,
        "body": build_comment_body(run_url=run_url, scans_by_job=scans_by_job),
        "comment_marker": COMMENT_MARKER,
    }


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-dir", default="build-scan-urls")
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--server-url", required=True)
    return parser.parse_args(argv[1:])


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    payload = build_comment_payload(
        base_dir=Path(args.base_dir),
        run_id=args.run_id,
        repository=args.repository,
        server_url=args.server_url,
    )
    print(json.dumps(payload))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
