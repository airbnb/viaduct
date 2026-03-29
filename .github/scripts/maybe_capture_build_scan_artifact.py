#!/usr/bin/env python3
"""Persist a failing Gradle build scan payload to a workflow artifact."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from extract_build_scan_url import extract_build_scan_url


def maybe_capture_build_scan_artifact(
    *,
    log_file: Path,
    status: int,
    artifact_path: Path = Path("build-scan-artifact.json"),
    pr_number: str = "",
    label: str = "",
) -> str | None:
    """Stores the last build scan payload when the Gradle command fails."""
    if status == 0:
        return None

    scan_url = extract_build_scan_url(log_file.read_text())
    if not scan_url:
        return None

    artifact_path.parent.mkdir(parents=True, exist_ok=True)
    parsed_pr_number = int(pr_number) if pr_number.isdigit() else None
    artifact_path.write_text(
        json.dumps(
            {
                "pr_number": parsed_pr_number,
                "label": label,
                "url": scan_url,
            },
            indent=2,
        )
        + "\n"
    )

    return scan_url


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("log_file")
    parser.add_argument("--status", required=True, type=int)
    parser.add_argument("--artifact-path", default="build-scan-artifact.json")
    parser.add_argument("--pr-number", default=os.environ.get("PR_NUMBER", ""))
    parser.add_argument("--label", default="")
    return parser.parse_args(argv[1:])


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    scan_url = maybe_capture_build_scan_artifact(
        log_file=Path(args.log_file),
        status=args.status,
        artifact_path=Path(args.artifact_path),
        pr_number=str(args.pr_number),
        label=str(args.label),
    )
    if scan_url:
        print(f"Captured build scan: {scan_url}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
