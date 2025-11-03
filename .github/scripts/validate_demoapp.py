#!/usr/bin/env python3
"""
Validates a demo app before publishing to external repositories.

This script validates that:
1. The current branch is a release branch (release/v[major].[minor].[patch])
2. The demo app's viaductVersion matches the branch version
3. The demo app builds successfully on its own

Usage:
  python3 validate_demoapp.py <demoapp-name>
Example:
  python3 validate_demoapp.py starwars
"""

import sys
import subprocess
import re
from pathlib import Path


def get_current_branch():
    """Get the current git branch name."""
    result = subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def extract_version_from_branch(branch_name):
    """Extract version from branch name (e.g., release/v0.7.0 -> 0.7.0)."""
    match = re.match(r"^release/v(\d+\.\d+\.\d+)$", branch_name)
    if not match:
        return None
    return match.group(1)


def verify_release_branch():
    """Verify we're on a release branch and return the version."""
    branch_name = get_current_branch()

    version = extract_version_from_branch(branch_name)
    if not version:
        print(f"❌ Not on a release branch. Current branch: {branch_name}")
        print("   Expected branch format: release/v[major].[minor].[patch]")
        return None

    print(f"✅ Running on release branch: {branch_name}")
    print(f"   Release version: {version}")
    return version


def verify_demoapp_version(demoapp_dir, expected_version):
    """Verify the demo app's viaductVersion matches the expected version."""
    props_file = demoapp_dir / "gradle.properties"

    if not props_file.exists():
        print(f"❌ gradle.properties not found at {props_file}")
        return False

    content = props_file.read_text()
    version_match = re.search(r"^viaductVersion=(.+)$", content, re.MULTILINE)

    if not version_match:
        print(f"❌ viaductVersion not found in {props_file}")
        return False

    demoapp_version = version_match.group(1)

    if demoapp_version != expected_version:
        print(f"❌ Version mismatch!")
        print(f"   Expected version: {expected_version}")
        print(f"   Demo app version: {demoapp_version}")
        return False

    print(f"✅ Version matches: {expected_version}")
    return True


def verify_build(demoapp_dir):
    """Verify the demo app builds successfully."""
    print(f"Building demo app at {demoapp_dir}...")

    result = subprocess.run(
        ["./gradlew", "build", "--no-daemon"],
        cwd=demoapp_dir,
        capture_output=True,
        text=True,
    )

    if result.returncode != 0:
        print(f"❌ Build failed")
        print(f"stdout: {result.stdout}")
        print(f"stderr: {result.stderr}")
        return False

    print(f"✅ Build successful")
    return True


def main():
    if len(sys.argv) < 2:
        print("Error: Missing required argument")
        print("Usage: validate_demoapp.py <demoapp-name>")
        print("Example: validate_demoapp.py starwars")
        return 1

    demoapp_name = sys.argv[1]

    # Determine paths
    script_dir = Path(__file__).parent.resolve()
    repo_root = script_dir.parent.parent
    demoapp_dir = repo_root / "demoapps" / demoapp_name

    if not demoapp_dir.exists():
        print(f"❌ Demo app directory not found: {demoapp_dir}")
        return 1

    print(f"=== Validating {demoapp_name} ===")
    print()

    # Step 1: Verify we're on a release branch
    expected_version = verify_release_branch()
    if not expected_version:
        return 1
    print()

    # Step 2: Verify version matches
    print(f"Checking version in {demoapp_name}/gradle.properties...")
    if not verify_demoapp_version(demoapp_dir, expected_version):
        return 1
    print()

    # Step 3: Verify build
    print(f"Verifying {demoapp_name} builds independently...")
    if not verify_build(demoapp_dir):
        return 1
    print()

    print(f"✅ {demoapp_name} validation successful!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
