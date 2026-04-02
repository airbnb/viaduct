#!/usr/bin/env python3
"""Formats a CI alert message for posting to chat platforms.

Reads a JSON object from stdin with the following fields:

  Required:
    branch      - branch name (e.g. "main")
    server_url  - GitHub server URL (e.g. "https://github.com")
    repository  - repository full name (e.g. "org/repo")
    jobs        - non-empty array of failed jobs, each with:
                    name   - display name of the job/workflow
                    run_id - GitHub Actions run ID (used to construct the URL)

  Optional:
    sha         - commit SHA (for push-triggered failures)
    actor       - GitHub username who pushed (for push-triggered failures)

Prints formatted alert text to stdout. Single-job alerts produce one line;
multi-job alerts produce a header line followed by a bulleted list of jobs.

Exit codes:
  0 - success
  1 - invalid input
"""

import json
import sys


def format_alert(data: dict) -> str:
    branch = data["branch"]
    server_url = data["server_url"]
    repository = data["repository"]
    jobs = data["jobs"]

    sha = data.get("sha")
    actor = data.get("actor")

    commit_info = ""
    if sha and actor:
        commit_info = f" — commit `{sha[:7]}` by {actor}"
    elif sha:
        commit_info = f" — commit `{sha[:7]}`"
    elif actor:
        commit_info = f" — pushed by {actor}"

    def job_url(job):
        return f"{server_url}/{repository}/actions/runs/{job['run_id']}"

    if len(jobs) == 1:
        job = jobs[0]
        return f":red_circle: {job['name']} failed on `{branch}`{commit_info} ({job_url(job)})"

    header = f":red_circle: CI failed on `{branch}`{commit_info}"
    lines = [header] + [f"• {job['name']}: {job_url(job)}" for job in jobs]
    return "\n".join(lines)


def main():
    try:
        data = json.load(sys.stdin)
    except json.JSONDecodeError as e:
        print(f"Invalid JSON input: {e}", file=sys.stderr)
        return 1

    if not isinstance(data, dict):
        print("Input must be a JSON object", file=sys.stderr)
        return 1

    missing = [f for f in ("branch", "server_url", "repository", "jobs") if f not in data]
    if missing:
        print(f"Missing required fields: {', '.join(missing)}", file=sys.stderr)
        return 1

    jobs = data["jobs"]
    if not isinstance(jobs, list) or len(jobs) == 0:
        print("'jobs' must be a non-empty array", file=sys.stderr)
        return 1

    for i, job in enumerate(jobs):
        if not isinstance(job, dict) or "name" not in job or "run_id" not in job:
            print(f"jobs[{i}] must have 'name' and 'run_id' fields", file=sys.stderr)
            return 1

    print(format_alert(data))
    return 0


if __name__ == "__main__":
    sys.exit(main())
