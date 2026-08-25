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
                    tasks  - optional array of failing Gradle task paths

  Optional:
    sha         - commit SHA (for push-triggered failures)
    actor       - GitHub username who pushed (for push-triggered failures)
    attempt     - run attempt number; labeled only when above 1
    outcome     - "failure" (default), or "retry_success" for a run that a
                  retry recovered

Prints formatted alert text to stdout. Single-job alerts produce one line;
multi-job alerts produce a header line followed by a bulleted list of jobs. Any
job carrying tasks switches the whole message to the header form, listing each
job's tasks beneath it.

Exit codes:
  0 - success
  1 - invalid input
"""

import json
import sys

OUTCOMES = {
    "failure": (":red_circle:", "failed"),
    "retry_success": (":large_yellow_circle:", "needed a retry"),
}

MAX_TASKS_SHOWN = 3


def format_attempt_label(attempt) -> str:
    try:
        n = int(attempt)
    except (TypeError, ValueError):
        return ""
    return f", attempt {n}" if n > 1 else ""


def format_task_lines(tasks) -> list:
    shown = tasks[:MAX_TASKS_SHOWN]
    lines = [f"  `{task}`" for task in shown]
    hidden = len(tasks) - len(shown)
    if hidden:
        lines[-1] += f" +{hidden} more"
    return lines


def format_alert(data: dict) -> str:
    branch = data["branch"]
    server_url = data["server_url"]
    repository = data["repository"]
    jobs = data["jobs"]

    emoji, verb = OUTCOMES.get(data.get("outcome"), OUTCOMES["failure"])

    sha = data.get("sha")
    actor = data.get("actor")

    commit_info = ""
    if sha and actor:
        commit_info = f" — commit `{sha[:7]}` by {actor}"
    elif sha:
        commit_info = f" — commit `{sha[:7]}`"
    elif actor:
        commit_info = f" — pushed by {actor}"

    commit_info += format_attempt_label(data.get("attempt"))

    def job_url(job):
        return f"{server_url}/{repository}/actions/runs/{job['run_id']}"

    if len(jobs) == 1 and not jobs[0].get("tasks"):
        job = jobs[0]
        return f"{emoji} {job['name']} {verb} on `{branch}`{commit_info} ({job_url(job)})"

    lines = [f"{emoji} CI {verb} on `{branch}`{commit_info}"]
    for job in jobs:
        tasks = job.get("tasks") or []
        if not tasks:
            lines.append(f"• {job['name']}: {job_url(job)}")
            continue
        lines.append(f"• {job['name']}")
        lines.extend(format_task_lines(tasks))
        lines.append(f"  {job_url(job)}")
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
        if "tasks" in job and not isinstance(job["tasks"], list):
            print(f"jobs[{i}]['tasks'] must be an array", file=sys.stderr)
            return 1

    outcome = data.get("outcome")
    if outcome is not None and outcome not in OUTCOMES:
        print(f"'outcome' must be one of: {', '.join(sorted(OUTCOMES))}", file=sys.stderr)
        return 1

    print(format_alert(data))
    return 0


if __name__ == "__main__":
    sys.exit(main())
