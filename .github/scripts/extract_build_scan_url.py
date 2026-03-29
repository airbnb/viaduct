#!/usr/bin/env python3
"""Extract the last Gradle build scan URL from command output."""

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
