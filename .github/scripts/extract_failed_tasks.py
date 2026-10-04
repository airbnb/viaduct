#!/usr/bin/env python3
"""Extracts the Gradle tasks that failed from a GitHub Actions job log.

Reads a job log on stdin and prints one task path per line, deduplicated, in the
order they appear.

Task paths are posted publicly, so a line whose path falls outside TASK_FAILED
is skipped.

Prints nothing when no Gradle task failed. A job can fail without one, as
happens when a dependency repository returns HTTP 429 or a runner step dies
before Gradle starts.

Exit codes:
  0 - success, including when no failing task is present
"""

import re
import sys

TASK_FAILED = re.compile(r"> Task (:[A-Za-z0-9_.:-]+) FAILED")

# Job logs are coloured, and Windows runners emit CRLF. Both are stripped before matching.
ANSI = re.compile(r"\x1b\[[0-9;]*[a-zA-Z]")


def plain_text(log: str) -> str:
    return ANSI.sub("", log).replace("\r", "")


def extract_failed_tasks(log: str) -> list:
    tasks = []
    for match in TASK_FAILED.finditer(plain_text(log)):
        task = match.group(1)
        if task not in tasks:
            tasks.append(task)
    return tasks


def main():
    # Job logs carry arbitrary test output, which is not guaranteed to be valid UTF-8.
    log = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    for task in extract_failed_tasks(log):
        print(task)
    return 0


if __name__ == "__main__":
    sys.exit(main())
