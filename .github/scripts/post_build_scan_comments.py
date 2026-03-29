#!/usr/bin/env python3
"""Collect build scan artifacts and render a PR comment payload."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


COMMENT_MARKER = "<!-- viaduct-build-scan-comment -->"


def read_metadata_scan_entry(file_path: Path) -> tuple[int | None, str, str] | None:
    try:
        metadata = json.loads(file_path.read_text())
    except json.JSONDecodeError:
        return None

    pr_number = metadata.get("pr_number")
    label = metadata.get("label")
    url = metadata.get("url")
    if not label or not url:
        return None
    if isinstance(pr_number, str) and pr_number.isdigit():
        pr_number = int(pr_number)
    if pr_number is not None and not isinstance(pr_number, int):
        pr_number = None
    return pr_number, label, url


def collect_build_scan_data(base_dir: Path) -> tuple[int | None, dict[str, str]]:
    if not base_dir.exists():
        return None, {}

    pr_number = None
    scans_by_job: dict[str, str] = {}
    for file_path in sorted(base_dir.rglob("*.json")):
        metadata_entry = read_metadata_scan_entry(file_path)
        if metadata_entry is None:
            continue
        file_pr_number, label, url = metadata_entry
        if pr_number is None and file_pr_number is not None:
            pr_number = file_pr_number
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
    parser.add_argument("--base-dir", default="build-scan-artifacts")
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
