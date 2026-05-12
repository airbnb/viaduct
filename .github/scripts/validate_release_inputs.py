#!/usr/bin/env python3
"""
Validate and derive release workflow inputs for RC and final publications.

Usage:
    python3 validate_release_inputs.py --release-version 1.2.3 --rc-ver rc.1 --final false
    python3 validate_release_inputs.py --release-version 1.2.3 --final true --release-notes "..."

Exit codes:
    0 - script completed; inspect is_valid in output
"""

import argparse
import json
import os
import re
import sys

_RELEASE_VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+$")
_RC_VER_PATTERN = re.compile(r"^rc\.[1-9]\d*$")


def validate_release_inputs(
    release_version: str,
    rc_ver: str,
    final: bool,
    release_notes: str,
) -> dict:
    """
    Pure validation and derivation for release workflow inputs.

    Returns:
        dict with keys:
            publish_mode (str): "rc" or "release"
            published_version (str): X.Y.Z-rc.N or X.Y.Z
            source_ref (str): release/vX.Y.Z
            destination_branch (str): rc/vX.Y.Z-rc.N or main
            is_final (bool): whether this is a final release flow
            is_valid (bool): whether validation succeeded
            validation_error (str): human-readable error string, or ""
    """
    release_version = release_version.strip()
    rc_ver = rc_ver.strip()
    release_notes = release_notes.strip()

    result = {
        "publish_mode": "",
        "published_version": "",
        "source_ref": "",
        "destination_branch": "",
        "is_final": False,
        "is_valid": True,
        "validation_error": "",
    }

    if not _RELEASE_VERSION_PATTERN.fullmatch(release_version):
        result["is_valid"] = False
        result["validation_error"] = (
            f"release_version must be 'X.Y.Z', got '{release_version}'"
        )
        return result

    if final and rc_ver:
        result["is_valid"] = False
        result["validation_error"] = "Pass either final=true or rc_ver, not both."
        return result

    if not final and not rc_ver:
        result["is_valid"] = False
        result["validation_error"] = (
            "For non-final publication, rc_ver is required (example: rc.1)."
        )
        return result

    if rc_ver and not _RC_VER_PATTERN.fullmatch(rc_ver):
        result["is_valid"] = False
        result["validation_error"] = (
            f"rc_ver must look like 'rc.N' with N >= 1, got '{rc_ver}'"
        )
        return result

    if final and not release_notes:
        result["is_valid"] = False
        result["validation_error"] = "release_notes are required when final=true."
        return result

    source_ref = f"release/v{release_version}"
    if rc_ver:
        result.update({
            "publish_mode": "rc",
            "published_version": f"{release_version}-{rc_ver}",
            "source_ref": source_ref,
            "destination_branch": f"rc/v{release_version}-{rc_ver}",
            "is_final": False,
        })
    else:
        result.update({
            "publish_mode": "release",
            "published_version": release_version,
            "source_ref": source_ref,
            "destination_branch": "main",
            "is_final": True,
        })

    return result


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate and derive release workflow inputs."
    )
    parser.add_argument(
        "--release-version",
        required=True,
        help="Base release version in X.Y.Z format",
    )
    parser.add_argument(
        "--rc-ver",
        default="",
        help="RC suffix only, like rc.1",
    )
    parser.add_argument(
        "--final",
        required=True,
        choices=["true", "false"],
        help="Whether this is a final release flow",
    )
    parser.add_argument(
        "--release-notes",
        default="",
        help="Release notes body for final releases",
    )
    args = parser.parse_args()

    result = validate_release_inputs(
        release_version=args.release_version,
        rc_ver=args.rc_ver,
        final=args.final == "true",
        release_notes=args.release_notes,
    )

    print(json.dumps(result, indent=2))

    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a") as f:
            for key, value in result.items():
                out = json.dumps(value) if isinstance(value, bool) else value
                f.write(f"{key}={out}\n")

    if result["is_valid"]:
        print(
            f"✅ Valid: {result['publish_mode']} publication for "
            f"'{result['published_version']}' from '{result['source_ref']}'",
            file=sys.stderr,
        )
    else:
        print(f"❌ Invalid: {result['validation_error']}", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
