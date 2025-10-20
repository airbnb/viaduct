#!/usr/bin/env python3
"""
Publishes Viaduct Gradle plugins and Maven artifacts
Expects environment variables:
  - VIADUCT_GRADLE_PUBLISH_KEY
  - VIADUCT_GRADLE_PUBLISH_SECRET
  - VIADUCT_SONATYPE_USERNAME
  - VIADUCT_SONATYPE_PASSWORD
"""

import os
import sys
import subprocess
import json
import urllib.request
from pathlib import Path


def run_command(cmd, capture_output=False, check=True):
  """Run a shell command and return the result."""
  print(f"Running: {cmd}")
  result = subprocess.run(
    cmd,
    shell=True,
    capture_output=capture_output,
    text=True,
    check=check
  )
  if capture_output:
    return result.stdout.strip()
  return result


def check_version_published(version):
  """Check if a version is already published on Gradle Plugin Portal."""
  maven_metadata_url = "https://plugins.gradle.org/m2/com/airbnb/viaduct/module-gradle-plugin/maven-metadata.xml"

  try:
    with urllib.request.urlopen(maven_metadata_url) as response:
      metadata = response.read().decode('utf-8')
      return f"<version>{version}</version>" in metadata
  except Exception as e:
    print(f"Warning: Could not fetch Maven metadata: {e}")
    return False


def main():
  # Change to viaduct/oss directory
  script_dir = Path(__file__).parent.resolve()
  oss_dir = script_dir.parent.parent
  os.chdir(oss_dir)
  print(f"Working directory: {os.getcwd()}")

  print("\n=== VERSION CHECK ===")

  # Read VERSION file
  version_file_content = Path("VERSION").read_text().strip()
  print(f"VERSION file contains: {version_file_content}")

  # Check if this version is already published
  print(f"Checking if version {version_file_content} is already published...")

  if check_version_published(version_file_content):
    print(f"✅ Version {version_file_content} is already published - will publish SNAPSHOT")
    os.environ["VIADUCT_PLUGIN_SNAPSHOT"] = "true"
    should_release = False
  else:
    print(f"🚀 Version {version_file_content} is NEW - will publish as RELEASE")
    os.environ["VIADUCT_PLUGIN_SNAPSHOT"] = "false"
    should_release = True

  # Configure Gradle plugin publishing credentials
  os.environ["GRADLE_PUBLISH_KEY"] = os.environ.get("VIADUCT_GRADLE_PUBLISH_KEY", "")
  os.environ["GRADLE_PUBLISH_SECRET"] = os.environ.get("VIADUCT_GRADLE_PUBLISH_SECRET", "")

  # Configure Maven Central (Sonatype) publishing credentials
  os.environ["ORG_GRADLE_PROJECT_mavenCentralUsername"] = os.environ.get("VIADUCT_SONATYPE_USERNAME", "")
  os.environ["ORG_GRADLE_PROJECT_mavenCentralPassword"] = os.environ.get("VIADUCT_SONATYPE_PASSWORD", "")

  # Publish to Gradle Plugin Portal (releases only)
  if should_release:
    print(f"Publishing Gradle plugins as release version {version_file_content}...")
    print("\n=== GRADLE PLUGIN PORTAL PUBLISH ===")
    print("Publishing plugins to Gradle Plugin Portal (releases only)...")
    run_command("./gradlew gradle-plugins:publishPlugins --no-daemon --stacktrace")
  else:
    print("Publishing Gradle plugins with unique snapshot version...")
    print("Skipping Gradle Plugin Portal (snapshots not supported)...")

  # Publish to Maven Central (both releases and snapshots)
  print("\n=== MAVEN CENTRAL PUBLISH ===")
  print("Publishing to Maven Central (Sonatype)...")
  run_command("./gradlew publishToMavenCentral --no-daemon --stacktrace")
  print("Maven Central publish completed successfully!\n")

  # Extract the published version
  computed_version = run_command(
    "./gradlew printVersion --no-daemon --quiet | grep 'computedVersion=' | cut -d'=' -f2",
    capture_output=True
  )

  print("Computed version is :", computed_version)

  # Determine final IS_RELEASE flag
  if "SNAPSHOT" in computed_version:
    is_release = "false"
    print(f"Published snapshot version: {computed_version} (demo apps will NOT be synced)")
  else:
    is_release = "true"
    print(f"Published release version: {computed_version} (demo apps will be synced)")

    # Verify VERSION file matches published version
    if version_file_content != computed_version:
      print(f"⚠️  WARNING: VERSION file ({version_file_content}) does not match published version ({computed_version})")
      print(f"The demoapp versions will be updated to match the published version: {computed_version}")
    else:
      print("✅ VERSION file matches published version")

  print("\nGradle plugin publish completed successfully!")
  return 0


if __name__ == "__main__":
  try:
    sys.exit(main())
  except subprocess.CalledProcessError as e:
    print(f"Error: Command failed with exit code {e.returncode}", file=sys.stderr)
    sys.exit(e.returncode)
  except Exception as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)
