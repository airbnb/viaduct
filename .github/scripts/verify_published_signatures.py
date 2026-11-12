#!/usr/bin/env python3
"""Verifies that every file of a published snapshot version is signed with the release key.

Reads the coordinate files written by the `writePublishedCoordinates` Gradle
task, lists each coordinate's files from its snapshot `maven-metadata.xml`, and
downloads each file with its `.asc`. It first waits until each coordinate's
metadata lists a build stamped at or after --built-after, so an earlier build
cannot stand in for this run's. A signature passes only if gpg reports it good
and the signing key is in the committed key file.

Usage: verify_published_signatures.py --version 4.0.0-rc.1-SNAPSHOT
           --built-after 20261111.120000 --key .github/release-signing-key.asc
           [--root .]

Exit codes:
  0 - every listed file has a valid signature from the release key
  1 - the coordinate list is unusable, a coordinate shows no build of this run
      before the deadline or lists no files, a listed file or its signature is
      still missing at the deadline, or a signature is invalid or made by
      another key
"""

import argparse
import http.client
import os
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ElementTree

from probe_published_artifacts import (
    artifact_url,
    find_coordinate_files,
    find_problems,
    read_coordinates,
)

EXPIRY_WARNING_DAYS = 90
SECONDS_PER_DAY = 86400
FETCH_ATTEMPTS = 3
FETCH_RETRY_SECONDS = 5
DEFAULT_TIMEOUT_SECONDS = 600
DEFAULT_SLEEP_SECONDS = 30
BUILD_STAMP = re.compile(r"\d{8}\.\d{6}")


def snapshot_stamp(metadata_xml):
    """The build stamp and build number the snapshot metadata lists."""
    root = ElementTree.fromstring(metadata_xml)
    stamp = root.findtext("versioning/snapshot/timestamp") or ""
    build_number = root.findtext("versioning/snapshot/buildNumber")
    if not BUILD_STAMP.fullmatch(stamp) or not build_number:
        raise ValueError("the metadata has no valid <snapshot> timestamp and buildNumber")
    return stamp, build_number


def snapshot_files(artifact, metadata_xml):
    """The build stamp the snapshot metadata lists, and that build's file names without their signatures."""
    stamp, build_number = snapshot_stamp(metadata_xml)
    names = []
    for entry in ElementTree.fromstring(metadata_xml).iter("snapshotVersion"):
        extension = entry.findtext("extension")
        if not extension:
            raise ValueError("a <snapshotVersion> has no <extension>")
        if extension.endswith(".asc"):
            continue
        value = entry.findtext("value") or ""
        if not value.endswith(f"-{stamp}-{build_number}"):
            raise ValueError(f"it lists {value}, which is not the current build {stamp}-{build_number}")
        classifier = entry.findtext("classifier")
        suffix = f"-{classifier}" if classifier else ""
        names.append(f"{artifact}-{value}{suffix}.{extension}")
    return stamp, names


def gpg(gnupg_home, *args):
    return subprocess.run(
        ["gpg", "--homedir", gnupg_home, "--batch", "--status-fd", "1", *args],
        capture_output=True,
        text=True,
    )


def import_signing_keys(gnupg_home, key_path):
    """Imports the key file and returns (fingerprint, expiry epoch or None) of each key that can sign."""
    result = gpg(gnupg_home, "--import", key_path)
    if result.returncode != 0:
        raise ValueError(f"cannot import {key_path}: {result.stderr.strip()}")
    listing = gpg(gnupg_home, "--with-colons", "--list-keys").stdout.splitlines()
    keys = []
    for index, line in enumerate(listing):
        fields = line.split(":")
        if fields[0] not in ("pub", "sub") or "s" not in fields[11] or fields[1] == "r":
            continue
        fingerprint = listing[index + 1].split(":")[9]
        keys.append((fingerprint, int(fields[6]) if fields[6] else None))
    if not keys:
        raise ValueError(f"{key_path} contains no key that can sign")
    return keys


def signature_problem(gnupg_home, data_path, signature_path):
    """None if the signature is good, otherwise gpg's status keyword for why not."""
    result = gpg(gnupg_home, "--verify", signature_path, data_path)
    keywords = [
        line.split()[1]
        for line in result.stdout.splitlines()
        if line.startswith("[GNUPG:] ")
    ]
    if result.returncode == 0 and "GOODSIG" in keywords and "VALIDSIG" in keywords:
        return None
    for keyword in ("BADSIG", "NO_PUBKEY", "EXPKEYSIG", "REVKEYSIG", "ERRSIG"):
        if keyword in keywords:
            return keyword
    return f"gpg exit {result.returncode}"


def expiry_warnings(keys, now):
    return [
        f"release key {fingerprint} expires on"
        f" {time.strftime('%Y-%m-%d', time.gmtime(expires))}"
        for fingerprint, expires in keys
        if expires is not None and expires - now < EXPIRY_WARNING_DAYS * SECONDS_PER_DAY
    ]


def fetch(url, sleep=time.sleep):
    """The body at url, or None for a 404. Retries rate limits, server errors and timeouts."""
    for attempt in range(1, FETCH_ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            if (error.code != 429 and error.code < 500) or attempt == FETCH_ATTEMPTS:
                raise
        except (urllib.error.URLError, TimeoutError, http.client.HTTPException):
            if attempt == FETCH_ATTEMPTS:
                raise
        sleep(FETCH_RETRY_SECONDS * attempt)


def wait_for_build(metadata_url, artifact, built_after, deadline, fetch, now, sleep):
    """File names of the first build stamped at or after built_after; raises ValueError once the deadline passes."""
    while True:
        metadata = fetch(metadata_url)
        if metadata is None:
            problem = "no snapshot metadata"
        else:
            stamp, _ = snapshot_stamp(metadata)
            if stamp >= built_after:
                return snapshot_files(artifact, metadata)[1]
            problem = f"the metadata lists build {stamp}, older than {built_after}"
        if now() >= deadline:
            raise ValueError(problem)
        sleep(max(0, min(DEFAULT_SLEEP_SECONDS, deadline - now())))


def fetch_until_present(url, deadline, fetch, now, sleep):
    """The body at url, retrying a 404 until the deadline; None if it is still missing then."""
    while True:
        body = fetch(url)
        if body is not None or now() >= deadline:
            return body
        sleep(max(0, min(DEFAULT_SLEEP_SECONDS, deadline - now())))


def verify_coordinate(coordinate, built_after, deadline, gnupg_home, work_dir, fetch, now, sleep):
    """Problems found in one coordinate's files, and how many files were checked."""
    metadata_url = artifact_url(coordinate)
    directory = metadata_url.rsplit("/", 1)[0]
    names = wait_for_build(metadata_url, coordinate.artifact, built_after, deadline, fetch, now, sleep)
    if not names:
        return [f"snapshot metadata lists no files: {directory}"], 0
    data_path = os.path.join(work_dir, "payload")
    signature_path = os.path.join(work_dir, "payload.asc")
    problems = []
    for name in names:
        signature = fetch_until_present(f"{directory}/{name}.asc", deadline, fetch, now, sleep)
        if signature is None:
            problems.append(f"missing signature: {directory}/{name}.asc")
            continue
        data = fetch_until_present(f"{directory}/{name}", deadline, fetch, now, sleep)
        if data is None:
            problems.append(f"listed but missing: {directory}/{name}")
            continue
        with open(data_path, "wb") as handle:
            handle.write(data)
        with open(signature_path, "wb") as handle:
            handle.write(signature)
        problem = signature_problem(gnupg_home, data_path, signature_path)
        if problem:
            problems.append(f"{problem}: {directory}/{name}")
    return problems, len(names)


def main(argv=None, fetch=fetch, now=time.time, sleep=time.sleep):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True, help="the published -SNAPSHOT version")
    parser.add_argument(
        "--built-after",
        required=True,
        help="UTC yyyyMMdd.HHmmss recorded before publishing; older builds do not count",
    )
    parser.add_argument("--key", required=True, help="armored public key file of the release key")
    parser.add_argument("--root", default=".", help="directory to scan for coordinate files")
    parser.add_argument("--timeout-seconds", type=int, default=DEFAULT_TIMEOUT_SECONDS)
    args = parser.parse_args(argv)

    if not args.version.endswith("-SNAPSHOT"):
        print(f"::error::{args.version} is not a -SNAPSHOT version")
        return 1
    if not BUILD_STAMP.fullmatch(args.built_after):
        print(f"::error::--built-after {args.built_after!r} is not yyyyMMdd.HHmmss")
        return 1

    try:
        coordinates = read_coordinates(find_coordinate_files(args.root))
    except ValueError as error:
        print(f"::error::malformed coordinate file: {error}")
        return 1
    problems = find_problems(coordinates, args.version)
    if problems:
        for problem in problems:
            print(f"::error::{problem}")
        return 1

    with tempfile.TemporaryDirectory() as gnupg_home, tempfile.TemporaryDirectory() as work_dir:
        os.chmod(gnupg_home, 0o700)
        try:
            keys = import_signing_keys(gnupg_home, args.key)
        except ValueError as error:
            print(f"::error::{error}")
            return 1
        for warning in expiry_warnings(keys, int(now())):
            print(f"::warning::{warning}")

        failures = []
        checked = 0
        deadline = now() + args.timeout_seconds
        for coordinate in coordinates:
            try:
                found, count = verify_coordinate(
                    coordinate, args.built_after, deadline, gnupg_home, work_dir, fetch, now, sleep
                )
            except (OSError, http.client.HTTPException, ElementTree.ParseError, ValueError) as error:
                found, count = [f"error checking {artifact_url(coordinate)}: {error}"], 0
            checked += count
            failures.extend(found)
            mark = "❌" if found else "✅"
            print(f"  {mark} {count} files {coordinate.group}:{coordinate.artifact}")

    if failures:
        for failure in failures:
            print(f"::error::{failure}")
        return 1
    fingerprints = ", ".join(fingerprint for fingerprint, _ in keys)
    print(
        f"✅ all {checked} files of {len(coordinates)} coordinates are signed by"
        f" {fingerprints}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
