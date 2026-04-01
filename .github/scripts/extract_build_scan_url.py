#!/usr/bin/env python3
"""Extract the last Gradle build scan URL from command output."""

# Workflow-facing CLI contract:
# - Arguments: zero or one positional argument. With no argument, read command output from stdin.
#   With one argument, read the log text from that file path.
# - Behavior: print the last Gradle build scan URL found in the input and exit 0. If no URL is
#   found, print nothing and still exit 0 so callers can treat "no scan present" as a normal case.
# - Error handling: invalid CLI usage returns 2 via the explicit usage check. File read failures are
#   not caught here and will surface as a non-zero process exit with a Python traceback.
# - Non-zero exit for callers means the script could not read its declared input or was invoked
#   incorrectly; it does not mean "no build scan URL was found".

from __future__ import annotations

import re
import sys
from pathlib import Path


BUILD_SCAN_PATTERN = re.compile(
    r"https://(?:gradle\.com|scans\.gradle\.com)/s/[A-Za-z0-9]+"
)


def extract_build_scan_url(text: str) -> str | None:
    """Returns the last build scan URL found in text, if any."""
    matches = BUILD_SCAN_PATTERN.findall(text)
    return matches[-1] if matches else None


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        print(f"Usage: {argv[0]} [log-file]", file=sys.stderr)
        return 2

    if len(argv) == 2:
        text = Path(argv[1]).read_text()
    else:
        text = sys.stdin.read()

    url = extract_build_scan_url(text)
    if url:
        print(url)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
