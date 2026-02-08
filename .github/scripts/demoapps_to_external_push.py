#!/usr/bin/env python3
"""
Generic script to push demo apps from airbnb/viaduct to viaduct-dev org repositories.
This script is called by the individual demo app wrapper scripts.
Usage: demoapp_to_external_push.py <demoapp-name> <github-repo>
Example: demoapp_to_external_push.py starwars viaduct-dev/starwars
"""

import os
import sys
import subprocess
import re
import atexit
from pathlib import Path


class DemoAppPublisher:
    """Handles publishing a demo app to an external GitHub repository."""

    def __init__(self, demoapp_name, github_repo):
        self.demoapp_name = demoapp_name
        self.github_repo = github_repo
        self.script_dir = Path(__file__).parent.resolve()
        self.repo_root = self.script_dir.parent.parent
        self.demoapps_dir = self.repo_root / "demoapps"
        self.demoapp_dir = self.demoapps_dir / demoapp_name
        self.copybara_config = self.repo_root / ".github" / "copybara" / "copy.bara.sky"

        # Detect if running in CI
        self.is_ci = self.detect_ci_environment()

        # Determine authentication method based on environment
        self.github_token = os.environ.get("VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN")
        if self.is_ci:
            # In CI, always use HTTPS with token
            if not self.github_token:
                print("Warning: Running in CI but VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN is not set")
            self.destination_repo = f"https://github.com/{github_repo}.git"
            self.auth_method = "HTTPS with token (CI)"
        else:
            # Local execution: always use SSH
            self.destination_repo = f"git@github.com:{github_repo}.git"
            self.auth_method = "SSH (local)"

        print(f"Using {self.auth_method} for {self.destination_repo}")

        # Register cleanup handler
        atexit.register(self.cleanup)

        # Track what we modified
        self.netrc_modified = False
        self.source_repo = None

    @staticmethod
    def detect_ci_environment():
        """Detect if running in a CI environment."""
        # Common CI environment variables
        ci_indicators = [
            "CI",  # Generic CI indicator
            "GITHUB_ACTIONS",  # GitHub Actions
            "JENKINS_HOME",  # Jenkins
            "CIRCLECI",  # CircleCI
            "TRAVIS",  # Travis CI
            "GITLAB_CI",  # GitLab CI
            "BUILDKITE",  # Buildkite
        ]

        # Check for standard CI environment variables
        for indicator in ci_indicators:
            if os.environ.get(indicator):
                return True

        return False

    def cleanup(self):
        """Restore modified files on exit."""
        # Clean up netrc if we added to it
        if self.netrc_modified:
            netrc_path = Path.home() / ".netrc"
            if netrc_path.exists():
                try:
                    content = netrc_path.read_text()
                    # Remove our section
                    content = re.sub(
                        r"# BEGIN TEMP GIT ACCESS\n.*?# END TEMP GIT ACCESS\n",
                        "",
                        content,
                        flags=re.DOTALL,
                    )
                    netrc_path.write_text(content)
                except Exception as e:
                    print(f"Warning: Could not clean up .netrc: {e}")

        # Restore modified gradle.properties
        gradle_props = self.demoapp_dir / "gradle.properties"
        if gradle_props.exists():
            try:
                subprocess.run(
                    ["git", "checkout", "--", str(gradle_props)],
                    cwd=self.script_dir,
                    capture_output=True,
                    check=False,
                )
            except Exception:
                pass

    def setup_netrc(self):
        """Set up .netrc for HTTPS authentication (CI only)."""
        if not self.is_ci:
            # Local execution uses SSH, no netrc needed
            return

        if not self.github_token:
            # No token available, skip netrc setup
            return

        netrc_path = Path.home() / ".netrc"
        netrc_entry = f"""# BEGIN TEMP GIT ACCESS
machine github.com
login x-access-token
password {self.github_token}
# END TEMP GIT ACCESS
"""

        with open(netrc_path, "a") as f:
            f.write(netrc_entry)

        netrc_path.chmod(0o600)
        self.netrc_modified = True

    def determine_source_repo(self):
        """Determine source repository location."""
        result = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=self.script_dir,
            capture_output=True,
            text=True,
            check=True,
        )
        git_root = result.stdout.strip()
        if not git_root:
            raise RuntimeError("Could not determine git repository root")

        source_repo = f"file://{git_root}"
        print(f"Using repository location: {source_repo}")
        return source_repo

    def update_gradle_properties(self, published_version):
        """Update gradle.properties with the published version."""
        props_file = self.demoapp_dir / "gradle.properties"
        print(
            f"Updating {self.demoapp_name} gradle.properties to use published version: {published_version}"
        )

        content = props_file.read_text()
        content = re.sub(
            r"^viaductVersion=.*",
            f"viaductVersion={published_version}",
            content,
            flags=re.MULTILINE,
        )
        props_file.write_text(content)

    def run_copybara(self):
        """Run copybara to sync the demo app using shared config."""
        # Workflow name matches the pattern airbnb-viaduct-to-<demoapp>
        # These are generated dynamically in the copybara config
        workflow_name = f"airbnb-viaduct-to-{self.demoapp_name}"

        # Prepare destination URL with authentication for CI
        if self.is_ci and self.github_token:
            # CI: Use HTTPS with token
            final_repo = self.destination_repo.replace(
                "https://", f"https://x-access-token:{self.github_token}@"
            )
        else:
            # Local: Use SSH (no modification needed)
            final_repo = self.destination_repo

        print(f"Running copybara for {self.demoapp_name} (workflow: {workflow_name})")
        print(f"Environment: {'CI' if self.is_ci else 'Local'}")
        print(f"Source: {self.source_repo}")
        print(f"Destination: {self.destination_repo}")
        print(f"Config: {self.copybara_config}")

        # Build copybara command
        # Copybara requires: copybara migrate <config> <workflow> [options]
        # Origin is the current checked out repository (copybara runs from repo root)
        # Destination URL is overridden at runtime to handle CI vs local differences
        # Note: --config-root is automatically added by tools/copybara/run
        cmd = [
            "tools/copybara/run",
            "migrate",
            str(self.copybara_config),
            workflow_name,
            f"--git-destination-url={final_repo}",
            "--git-committer-email",
            "viabot@ductworks.io",
            "--git-committer-name",
            "ViaBot",
            "--force",  # Force migration even if last-rev cannot be found
        ]

        result = subprocess.run(cmd, check=False)

        # Google's copybara returns 4 for NO_OP (no changes to sync)
        # See: https://github.com/google/copybara/blob/master/copybara/integration/tool_test.sh#L24
        NO_OP_EXIT_CODE = 4

        if result.returncode == 0 or result.returncode == NO_OP_EXIT_CODE:
            print(f"Successfully synced {self.demoapp_name} to external repository")
            return 0
        else:
            print(
                f"Failed to sync {self.demoapp_name} (exit code: {result.returncode})"
            )
            return result.returncode

    def verify_individual_build(self):
        """Verify that the demo app builds independently."""
        print(f"Verifying {self.demoapp_name} builds independently...")

        # Change to the demoapp directory and run gradlew
        result = subprocess.run(
            ["./gradlew", "build", "--no-daemon"],
            cwd=self.demoapp_dir,
            capture_output=True,
            text=True,
        )

        if result.returncode != 0:
            print(f"❌ {self.demoapp_name} failed to build independently")
            print(f"stdout: {result.stdout}")
            print(f"stderr: {result.stderr}")
            return False

        print(f"✅ {self.demoapp_name} builds successfully")
        return True

    def verify_release_version_matches_branch(self):
        """Verify that the release version in gradle.properties matches the branch name."""
        # Get current branch name
        result = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=self.script_dir,
            capture_output=True,
            text=True,
            check=True,
        )
        branch_name = result.stdout.strip()

        # Check if we're on a release branch (release/vX.Y.Z)
        if not branch_name.startswith("release/v"):
            print(f"❌ Not on a release branch. Current branch: {branch_name}")
            print("   Expected branch format: release/v[major].[minor].[patch]")
            return False

        # Extract version from branch name (e.g., "release/v1.2.3" -> "1.2.3")
        branch_version = branch_name[len("release/v"):]

        # Read version from gradle.properties
        props_file = self.demoapp_dir / "gradle.properties"
        if not props_file.exists():
            print(f"❌ gradle.properties not found at {props_file}")
            return False

        content = props_file.read_text()
        version_match = re.search(r"^viaductVersion=(.+)$", content, re.MULTILINE)

        if not version_match:
            print(f"❌ viaductVersion not found in {props_file}")
            return False

        demoapp_version = version_match.group(1)

        # Compare versions
        if demoapp_version != branch_version:
            print(f"❌ Version mismatch!")
            print(f"   Branch version: {branch_version}")
            print(f"   Demo app version: {demoapp_version}")
            return False

        print(f"✅ Version matches branch: {branch_version}")
        return True

    def publish(self):
        """Main publish workflow."""
        # Verify we're on a release branch and version matches
        if not self.verify_release_version_matches_branch():
            return 1

        # Verify the demo app builds independently
        if not self.verify_individual_build():
            return 1

        # Validate auth for CI
        if self.is_ci and not self.github_token:
            print(
                "Error: VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN environment variable is required in CI"
            )
            return 1

        # Setup authentication (CI only - sets up .netrc for HTTPS)
        self.setup_netrc()

        # Determine source repository
        self.source_repo = self.determine_source_repo()

        # Extract version from branch name (e.g., release/v0.7.0 -> 0.7.0)
        result = subprocess.run(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            cwd=self.script_dir,
            capture_output=True,
            text=True,
            check=True,
        )
        branch_name = result.stdout.strip()
        if branch_name.startswith("release/v"):
            published_version = branch_name[len("release/v"):]
            print(f"Using version from branch: {published_version}")
        else:
            print(f"Error: Not on a release branch. Current branch: {branch_name}")
            return 1

        # Update gradle.properties with published version
        self.update_gradle_properties(published_version)

        # Run copybara with shared config
        return self.run_copybara()


def main():
    if len(sys.argv) < 3:
        print("Error: Missing required arguments")
        print("Usage: demoapp_to_external_push.py <demoapp-name> <github-repo>")
        print("Example: demoapp_to_external_push.py starwars viaduct-dev/starwars")
        return 1

    demoapp_name = sys.argv[1]
    github_repo = sys.argv[2]

    publisher = DemoAppPublisher(demoapp_name, github_repo)
    return publisher.publish()


if __name__ == "__main__":
    sys.exit(main())
