#!/usr/bin/env python3
"""Names the known infrastructure failure, if any, in a GitHub Actions job log.

Reads a job log on stdin and prints one short label: the highest-priority known
signature present, or UNRECOGNIZED when none matches. A repository HTTP status
outranks every other signature. A label names what the log shows, not a guess
at why it happened.

Exit codes:
  0 - success, including when no signature matches
"""

import re
import sys

from extract_failed_tasks import plain_text

UNRECOGNIZED = "no known infrastructure cause"

REPOSITORY_STATUS = re.compile(
    r"Could not (?:GET|HEAD) 'https?://(?:[^/'@]*@)?([\w.:-]+)[^']*'\. Received status code (\d{3})"
)

SIGNATURES = [
    (re.compile(r"Unable to download artifact\(s\)"), lambda m: "GitHub artifact download failed"),
    (re.compile(r"java\.net\.UnknownHostException: ([\w.-]+)"), lambda m: f"DNS lookup failed ({m.group(1)})"),
    (
        re.compile(r"Unable to connect to the child process '(.+?)(?: \d+)?'"),
        lambda m: f"Gradle child process never connected ({m.group(1)})",
    ),
]


def classify_failure(log: str) -> str:
    plain = plain_text(log)
    match = REPOSITORY_STATUS.search(plain)
    if match:
        return f"HTTP {match.group(2)} from {match.group(1)}"
    for pattern, label in SIGNATURES:
        match = pattern.search(plain)
        if match:
            return label(match)
    return UNRECOGNIZED


def main():
    # Job logs carry arbitrary test output, which is not guaranteed to be valid UTF-8.
    log = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    print(classify_failure(log))
    return 0


if __name__ == "__main__":
    sys.exit(main())
