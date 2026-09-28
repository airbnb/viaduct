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
from urllib.parse import urlsplit

from extract_failed_tasks import plain_text

UNRECOGNIZED = "no known infrastructure cause"

REPOSITORY_STATUS = re.compile(r"Could not (?:GET|HEAD) '(https?://[^']+)'\. Received status code (\d{3})")
HOSTNAME = re.compile(r"[\w.:-]+")

SIGNATURES = [
    (re.compile(r"Unable to download artifact\(s\)"), lambda m: "GitHub artifact download failed"),
    (re.compile(r"java\.net\.UnknownHostException: ([\w.-]+)"), lambda m: f"DNS lookup failed for {m.group(1)}"),
    (
        re.compile(r"Unable to connect to the child process '(Gradle [A-Za-z ]{1,40}?)(?: \d+)?'"),
        lambda m: f"{m.group(1)} never connected",
    ),
]


def repository_host(url: str) -> str:
    # The label is posted publicly, so only a parsed hostname may reach it, never URL credentials.
    try:
        host = urlsplit(url).hostname
    except ValueError:
        host = None
    return host if host and HOSTNAME.fullmatch(host) else "a dependency repository"


def classify_failure(log: str) -> str:
    plain = plain_text(log)
    match = REPOSITORY_STATUS.search(plain)
    if match:
        return f"HTTP {match.group(2)} from {repository_host(match.group(1))}"
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
