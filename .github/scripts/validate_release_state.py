#!/usr/bin/env python3
"""
Pure branch/version validation helper for Viaduct release workflows.

Validates the relationship between the current git branch, the VERSION file,
and the intended publication mode (snapshot vs release). Outputs structured
JSON consumable by GitHub Actions workflows via fromJSON().

Usage:
    python3 validate_release_state.py --mode snapshot
    python3 validate_release_state.py --mode release --branch release/v0.27.0
    python3 validate_release_state.py --mode snapshot --branch main --version-file VERSION

Exit codes:
    0 - validation passed (is_valid=true in output)
    1 - validation failed (is_valid=false in output)
"""

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

_RELEASE_BRANCH_PATTERN = re.compile(r"^release/v(\d+\.\d+\.\d+)$")


def _find_version_file(start_dir: Path) -> Path:
    """Walk up from start_dir to find the VERSION file."""
    current = start_dir.resolve()
    while True:
        candidate = current / "VERSION"
        if candidate.exists():
            return candidate
        parent = current.parent
        if parent == current:
            raise FileNotFoundError(f"VERSION file not found starting from {start_dir}")
        current = parent


def _get_current_branch() -> str:
    """Detect the current git branch. Only called when --branch is not provided."""
    result = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def _parse_release_branch(branch: str) -> Optional[str]:
    """Extract X.Y.Z from a release/vX.Y.Z branch name, or return None."""
    match = _RELEASE_BRANCH_PATTERN.fullmatch(branch)
    return match.group(1) if match else None


def _version_matches_base(effective_version: str, base: str) -> bool:
    """True if effective_version equals base exactly, or is base followed by '-' (e.g. base-SNAPSHOT, base-rc.1)."""
    return effective_version == base or effective_version.startswith(f"{base}-")


def _make_snapshot_tag() -> str:
    """Generate a snapshot tag like snapshot/20260403T120000Z."""
    now = datetime.now(tz=timezone.utc)
    return f"snapshot/{now.strftime('%Y%m%dT%H%M%SZ')}"


def validate_release_state(mode: str, branch: str, version_content: str, snapshot_tag: str) -> dict:
    """
    Pure validation function — no I/O, no subprocess, no side effects.

    Args:
        mode: "snapshot" or "release"
        branch: git branch name (e.g. "main", "release/v0.27.0", "feature/foo")
        version_content: raw content of the VERSION file (trailing whitespace is stripped)
        snapshot_tag: pre-generated snapshot tag (e.g. from _make_snapshot_tag())

    Returns:
        dict with keys:
            is_release_branch (bool): whether branch matches release/vX.Y.Z
            base_release_version (str): X.Y.Z extracted from release branch, or ""
            effective_version (str): stripped content of VERSION file
            is_valid (bool): whether state is valid for the requested mode
            validation_error (str): human-readable error message, or "" if valid
            snapshot_tag (str): e.g. "snapshot/20260403T120000Z"
    """
    effective_version = version_content.strip()
    base_release_version = _parse_release_branch(branch)
    is_release_branch = base_release_version is not None

    is_valid = True
    validation_error = ""

    if is_release_branch:
        # On a release branch: VERSION must start with X.Y.Z from the branch name
        if not _version_matches_base(effective_version, base_release_version):
            is_valid = False
            validation_error = (
                f"VERSION '{effective_version}' does not match release branch "
                f"'release/v{base_release_version}' — expected version to start with "
                f"'{base_release_version}'"
            )
        elif mode == "release":
            # Release mode: VERSION must be exactly X.Y.Z (no suffix of any kind)
            if effective_version != base_release_version:
                is_valid = False
                validation_error = (
                    f"Release mode requires VERSION to be exactly '{base_release_version}', "
                    f"but got '{effective_version}'"
                )
        elif mode == "snapshot":
            # Snapshot mode on release branch: VERSION must end with -SNAPSHOT
            if not effective_version.endswith("-SNAPSHOT"):
                is_valid = False
                validation_error = (
                    f"Snapshot mode requires VERSION to end with '-SNAPSHOT', "
                    f"but got '{effective_version}'"
                )
    else:
        # Non-release branch
        if mode == "release":
            is_valid = False
            validation_error = (
                f"Release mode requires a 'release/vX.Y.Z' branch, "
                f"but current branch is '{branch}'"
            )
        elif mode == "snapshot":
            # Snapshot mode: VERSION must end with -SNAPSHOT
            if not effective_version.endswith("-SNAPSHOT"):
                is_valid = False
                validation_error = (
                    f"Snapshot mode requires VERSION to end with '-SNAPSHOT', "
                    f"but got '{effective_version}'"
                )

    return {
        "is_release_branch": is_release_branch,
        "base_release_version": base_release_version or "",
        "effective_version": effective_version,
        "is_valid": is_valid,
        "validation_error": validation_error,
        "snapshot_tag": snapshot_tag,
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate git branch and VERSION file state for Viaduct publication."
    )
    parser.add_argument(
        "--mode",
        required=True,
        choices=["snapshot", "release"],
        help="Publication mode: 'snapshot' or 'release'",
    )
    parser.add_argument(
        "--branch",
        default=None,
        help="Git branch name override (defaults to current branch via git rev-parse)",
    )
    parser.add_argument(
        "--version-file",
        default=None,
        help="Path to VERSION file (defaults to VERSION discovered by walking up from script location)",
    )
    args = parser.parse_args()

    # Resolve branch
    branch = args.branch if args.branch is not None else _get_current_branch()

    # Resolve VERSION file content
    if args.version_file:
        version_path = Path(args.version_file)
    else:
        version_path = _find_version_file(Path(__file__).parent)
    version_content = version_path.read_text()

    # Run pure validation
    result = validate_release_state(args.mode, branch, version_content, _make_snapshot_tag())

    # Output JSON to stdout
    print(json.dumps(result, indent=2))

    # Write GitHub Actions step outputs if GITHUB_OUTPUT is set
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as f:
            for key, value in result.items():
                out = json.dumps(value) if isinstance(value, bool) else value
                f.write(f"{key}={out}\n")

    if result["is_valid"]:
        print(
            f"✅ Valid: {args.mode} mode, branch '{branch}', "
            f"version '{result['effective_version']}'",
            file=sys.stderr,
        )
    else:
        print(f"❌ Invalid: {result['validation_error']}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
