#!/usr/bin/env python3
"""
Validates workflow dispatch inputs os and java_versions.

Reads OS_INPUT and JAVA_INPUT environment variables and verifies each is either
empty (will use workflow default) or a valid JSON array. os must be an array of
strings; java_versions may be an array of strings, integers, or a mix.

If these inputs are malformed and yet put into the matrix configuration of
a job, the job fails with basically no diagnostic information.

Exit codes:
  0 - all inputs are valid
  1 - one or more inputs are invalid
"""

import json
import os
import sys

OS_DEFAULT = ["ubuntu-latest", "macos-latest"]
JAVA_DEFAULT = ["17", "11", "21"]


def validate_json_array(name: str, value: str, allow_numbers: bool = False) -> list:
    """
    Validates that value is a non-empty JSON array.

    If allow_numbers is False (default), all elements must be strings.
    If allow_numbers is True, elements may be strings or integers.

    Returns the parsed array on success, raises ValueError on failure.
    """
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError as e:
        raise ValueError(
            f"'{name}' is not valid JSON: {e}\n"
            f"  Got: {value!r}\n"
            f"  Hint: use a JSON array of strings, e.g. [\"ubuntu-latest\"]"
        )

    if not isinstance(parsed, list):
        raise ValueError(
            f"'{name}' must be a JSON array, got {type(parsed).__name__}\n"
            f"  Got: {value!r}"
        )

    if len(parsed) == 0:
        raise ValueError(
            f"'{name}' must not be an empty array\n"
            f"  Got: {value!r}"
        )

    valid_types = (str, int) if allow_numbers else (str,)
    invalid = [item for item in parsed if isinstance(item, bool) or not isinstance(item, valid_types)]
    if invalid:
        expected = "strings or integers" if allow_numbers else "strings"
        raise ValueError(
            f"'{name}' must be an array of {expected}, but found invalid values: {invalid!r}\n"
            f"  Got: {value!r}"
        )

    return parsed


def write_outputs(os_list: list, java_list: list):
    """Write coverage point and wide matrix to GITHUB_OUTPUT."""
    github_output = os.environ.get("GITHUB_OUTPUT", "")
    if not github_output:
        return
    coverage_os = str(os_list[0])
    coverage_java = str(java_list[0])
    wide_combinations = [
        {"os": str(os_val), "java": str(java_val)}
        for os_val in os_list
        for java_val in java_list
        if not (str(os_val) == coverage_os and str(java_val) == coverage_java)
    ]
    has_wide = "true" if wide_combinations else "false"
    with open(github_output, "a") as f:
        f.write(f"coverage_os={coverage_os}\n")
        f.write(f"coverage_java={coverage_java}\n")
        f.write(f"has_wide={has_wide}\n")
        f.write(f"wide_matrix={json.dumps(wide_combinations)}\n")
    print(f"📍 Coverage point: os={coverage_os}, java={coverage_java}")
    print(f"🔲 Wide matrix: {len(wide_combinations)} coordinate(s), has_wide={has_wide}")


def main():
    os_input = os.environ.get("OS_INPUT", "")
    java_input = os.environ.get("JAVA_INPUT", "")

    errors = []
    effective_os = OS_DEFAULT
    effective_java = JAVA_DEFAULT

    if os_input:
        try:
            effective_os = validate_json_array("os", os_input)
            print(f"✅ os: {os_input}")
        except ValueError as e:
            errors.append(str(e))
            print(f"❌ os: {e}", file=sys.stderr)

    if java_input:
        try:
            effective_java = validate_json_array("java_versions", java_input, allow_numbers=True)
            print(f"✅ java_versions: {java_input}")
        except ValueError as e:
            errors.append(str(e))
            print(f"❌ java_versions: {e}", file=sys.stderr)

    if errors:
        print(f"\n{len(errors)} input error(s) found. See above for details.", file=sys.stderr)
        return 1

    if not os_input and not java_input:
        print("ℹ️  No inputs provided, using workflow defaults.")

    write_outputs(effective_os, effective_java)
    return 0


if __name__ == "__main__":
    sys.exit(main())
