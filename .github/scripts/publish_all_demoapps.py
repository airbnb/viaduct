#!/usr/bin/env python3
"""
Publishes all demo apps to their external repositories

Usage:
  python3 publish_all_demoapps.py

This script should only be run on release branches (release/v[major].[minor].[patch]).
The version is automatically extracted from the branch name (e.g., release/v0.7.0 -> 0.7.0).
Each demo app will verify that its version matches the branch name before publishing.

Authentication:
  - CI: Uses HTTPS with token (requires VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN)
  - Local: Uses SSH (requires SSH keys configured for GitHub)
"""

import sys
import subprocess
from pathlib import Path
import re


def publish_demoapp(python_script, demoapp_name, github_repo):
    """Run the Python demoapp publisher and return success/failure."""
    try:
        subprocess.run(
            ["python3", str(python_script), demoapp_name, github_repo], check=True
        )
        return True
    except subprocess.CalledProcessError:
        return False


def verify_release_branch():
    """Verify we're on a release branch."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        )
        branch_name = result.stdout.strip()

        if not re.match(r"^release/v\d+\.\d+\.\d+$", branch_name):
            print(f"❌ This script must be run on a release branch")
            print(f"   Current branch: {branch_name}")
            print(f"   Expected format: release/v[major].[minor].[patch]")
            return False

        print(f"✅ Running on release branch: {branch_name}")
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ Failed to determine current branch: {e}")
        return False


def main():
    script_dir = Path(__file__).parent.resolve()
    demoapp_publisher = script_dir / "demoapps_to_external_push.py"

    # Verify we're on a release branch
    if not verify_release_branch():
        return 1

    print()
    print("=== PUBLISHING ALL DEMO APPS ===")
    print()

    # Define demo apps to publish (name, github_repo)
    demo_apps = [
        ("starwars", "viaduct-dev/starwars"),
        ("cli-starter", "viaduct-dev/cli-starter"),
        ("ktor-starter", "viaduct-dev/ktor-starter"),
    ]

    # Track failures
    failed_apps = []

    # Publish each demo app
    for app_name, github_repo in demo_apps:
        print(f">>> Publishing {app_name} demo app...")
        if publish_demoapp(demoapp_publisher, app_name, github_repo):
            print(f"✅ {app_name} published successfully")
        else:
            print(f"❌ {app_name} publish failed")
            failed_apps.append(app_name)
        print()

    # Summary
    print("=== DEMO APP PUBLISH SUMMARY ===")
    if not failed_apps:
        print("✅ All demo apps published successfully!")
        return 0
    else:
        print("❌ The following demo apps failed to publish:")
        for app in failed_apps:
            print(f"  - {app}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
