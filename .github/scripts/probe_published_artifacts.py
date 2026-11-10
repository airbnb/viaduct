#!/usr/bin/env python3
"""Waits for a release's published artifacts to become visible on their CDNs.

Reads the coordinate files written by the `writePublishedCoordinates` Gradle
task, derives one URL per coordinate, and polls every URL that is not yet
visible until a single global deadline expires. Deriving the list from the build
keeps it from drifting away from what the release actually published.

A -SNAPSHOT version is probed in the Central snapshot repository, whatever
repository its coordinate names.

With --require-unpublished, checks once that no coordinate is published yet,
so a release candidate is never overwritten.

Usage: probe_published_artifacts.py --version 2.0.0 [--root .] [--require-unpublished]

Exit codes:
  0 - every derived artifact is visible (or, with --require-unpublished, absent)
  1 - the derived list is unusable, artifacts are missing at the deadline, or
      (with --require-unpublished) an artifact is published or its absence
      could not be confirmed
"""

import argparse
import collections
import http.client
import os
import sys
import time
import urllib.error
import urllib.request

COORDINATE_DIR = "build/reports/publication"
COORDINATE_FILE_NAME = "coordinates.txt"

REPOSITORY_BASES = {
    "central": "https://repo.maven.apache.org/maven2",
    "portal": "https://plugins.gradle.org/m2",
}

SNAPSHOT_REPOSITORY_BASE = "https://central.sonatype.com/repository/maven-snapshots"

DEFAULT_TIMEOUT_SECONDS = 1800
DEFAULT_SLEEP_SECONDS = 30

Coordinate = collections.namedtuple(
    "Coordinate", ["repository", "group", "artifact", "version"]
)


def find_coordinate_files(root):
    """Find every coordinates.txt the Gradle task wrote under root."""
    found = []
    for dirpath, _, filenames in os.walk(root):
        if COORDINATE_FILE_NAME not in filenames:
            continue
        if not dirpath.replace("\\", "/").endswith(COORDINATE_DIR):
            continue
        found.append(os.path.join(dirpath, COORDINATE_FILE_NAME))
    return sorted(found)


def parse_coordinates(text):
    coordinates = []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line:
            continue
        fields = line.split()
        if len(fields) != 2:
            raise ValueError(
                f"line {number}: expected '<repository> <group>:<artifact>:"
                f"<version>', got {line!r}"
            )
        repository, gav = fields
        if repository not in REPOSITORY_BASES:
            raise ValueError(
                f"line {number}: unknown repository {repository!r}; expected one"
                f" of {', '.join(sorted(REPOSITORY_BASES))}"
            )
        parts = gav.split(":")
        if len(parts) != 3 or not all(parts):
            raise ValueError(
                f"line {number}: expected '<group>:<artifact>:<version>', got"
                f" {gav!r}"
            )
        coordinates.append(Coordinate(repository, *parts))
    return coordinates


def read_coordinates(paths):
    seen = []
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            for coordinate in parse_coordinates(handle.read()):
                if coordinate not in seen:
                    seen.append(coordinate)
    return seen


def find_problems(coordinates, expected_version):
    """Reasons the derived list cannot be trusted as a release gate.

    An empty list, or one missing a whole repository, would let the job pass
    without probing what it was meant to probe.
    """
    problems = []
    if not coordinates:
        problems.append(
            "no coordinates found; writePublishedCoordinates produced nothing"
        )
        return problems
    repositories = {coordinate.repository for coordinate in coordinates}
    for required in sorted(REPOSITORY_BASES):
        if required not in repositories:
            problems.append(f"no '{required}' coordinates found")
    unexpected = sorted(
        {c.version for c in coordinates if c.version != expected_version}
    )
    if unexpected:
        problems.append(
            f"coordinates carry version {', '.join(unexpected)}, expected"
            f" {expected_version}"
        )
    return problems


def artifact_url(coordinate):
    group_path = coordinate.group.replace(".", "/")
    if coordinate.version.endswith("-SNAPSHOT"):
        # Snapshot file names carry a build timestamp; the metadata file does not.
        base = SNAPSHOT_REPOSITORY_BASE
        file_name = "maven-metadata.xml"
    else:
        base = REPOSITORY_BASES[coordinate.repository]
        file_name = f"{coordinate.artifact}-{coordinate.version}.pom"
    return (
        f"{base}/{group_path}/{coordinate.artifact}/{coordinate.version}/"
        f"{file_name}"
    )


def find_published(urls, fetch):
    """Each URL whose absence a 404 does not confirm, with the status seen."""
    found = []
    for url in urls:
        status = fetch(url)
        if status == 404:
            print(f"  ✅ HTTP 404 {url}")
        else:
            print(f"  ❌ HTTP {status} {url}")
            found.append((url, status))
    return found


def http_status(url):
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (OSError, http.client.HTTPException):
        return 0


def probe(
    urls,
    timeout_seconds=DEFAULT_TIMEOUT_SECONDS,
    sleep_seconds=DEFAULT_SLEEP_SECONDS,
    fetch=http_status,
    now=time.monotonic,
    sleep=time.sleep,
):
    """Poll every URL until all are visible or the shared deadline passes.

    Returns each still-missing URL with the last status seen for it.
    """
    deadline = now() + timeout_seconds
    pending = list(urls)
    last_status = {}
    attempt = 0
    while True:
        attempt += 1
        still_pending = []
        for url in pending:
            status = fetch(url)
            last_status[url] = status
            if status == 200:
                print(f"  ✅ {url}")
            else:
                print(f"  ⏳ HTTP {status} {url}")
                still_pending.append(url)
        pending = still_pending
        if not pending:
            return []
        remaining = deadline - now()
        if remaining <= 0:
            return [(url, last_status[url]) for url in pending]
        print(
            f"  {len(pending)} of {len(urls)} still missing (attempt"
            f" {attempt}) — sleeping {sleep_seconds}s"
        )
        sleep(min(sleep_seconds, remaining))


def main(argv=None, fetch=http_status):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--version", required=True, help="version the release published"
    )
    parser.add_argument(
        "--root", default=".", help="directory to scan for coordinate files"
    )
    parser.add_argument(
        "--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS
    )
    parser.add_argument(
        "--sleep-seconds", type=int, default=DEFAULT_SLEEP_SECONDS
    )
    parser.add_argument(
        "--require-unpublished",
        action="store_true",
        help="fail unless every artifact is absent (HTTP 404)",
    )
    args = parser.parse_args(argv)

    paths = find_coordinate_files(args.root)
    try:
        coordinates = read_coordinates(paths)
    except ValueError as error:
        print(f"::error::malformed coordinate file: {error}")
        return 1

    problems = find_problems(coordinates, args.version)
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1

    # Logged so a release can be diffed against a known-good run.
    counts = collections.Counter(c.repository for c in coordinates)
    breakdown = ", ".join(f"{counts[name]} {name}" for name in sorted(counts))
    print(
        f"Derived {len(coordinates)} coordinates for {args.version} from"
        f" {len(paths)} projects ({breakdown})"
    )
    for path in paths:
        print(f"  {path}")

    urls = [artifact_url(coordinate) for coordinate in coordinates]
    if args.require_unpublished:
        found = find_published(urls, fetch=fetch)
        if found:
            for url, status in found:
                print(
                    f"::error::{args.version} may already be published (HTTP"
                    f" {status}, expected 404): {url}"
                )
            return 1
        print(f"✅ none of the {len(urls)} artifacts is published yet")
        return 0

    missing = probe(
        urls, args.timeout_seconds, args.sleep_seconds, fetch=fetch
    )
    if missing:
        minutes = args.timeout_seconds // 60
        for url, status in missing:
            print(
                f"::error::not visible after {minutes}m of polling (last status"
                f" HTTP {status}): {url}"
            )
        return 1
    print(f"✅ all {len(urls)} artifacts are visible")
    return 0


if __name__ == "__main__":
    sys.exit(main())
