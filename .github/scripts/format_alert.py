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
                    job_id - optional job ID; links straight to that job's log
                    tasks  - optional array of failing Gradle task paths
                    cause  - optional short label naming a known failure cause

  Optional:
    sha         - commit SHA (for push-triggered failures)
    actor       - GitHub username who pushed (for push-triggered failures)
    attempt     - run attempt number; labeled only when above 1
    summary_url - URL of the page carrying the --summary table; linked when the
                  alert is cut to fit
    outcome     - "failure" (default), "retry_success" for a run that a retry
                  recovered, or "retrying" for a run whose retry is under way

Prints formatted alert text to stdout, or with --summary a Markdown table for
the run page. Single-job alerts produce one line;
multi-job alerts produce a header line followed by a bulleted list of jobs. Any
job carrying tasks switches the whole message to the header form, listing each
job's tasks beneath it. Jobs that do not fit MAX_ALERT_CHARS are counted in a
closing line instead.

Exit codes:
  0 - success
  1 - invalid input
"""

import json
import sys

OUTCOMES = {
    "failure": (":red_circle:", "failed"),
    "retry_success": (":green_circle:", "passed on retry"),
    "retrying": (":repeat:", "is being retried"),
}

MAX_TASKS_SHOWN = 3

# Discord rejects messages longer than 2,000 characters.
MAX_ALERT_CHARS = 2000


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


def format_commit_info(data: dict) -> str:
    sha = data.get("sha")
    actor = data.get("actor")

    commit_info = ""
    if sha and actor:
        commit_info = f" — commit `{sha[:7]}` by {actor}"
    elif sha:
        commit_info = f" — commit `{sha[:7]}`"
    elif actor:
        commit_info = f" — pushed by {actor}"

    return commit_info + format_attempt_label(data.get("attempt"))


def job_url(data: dict, job: dict) -> str:
    run_url = f"{data['server_url']}/{data['repository']}/actions/runs/{job['run_id']}"
    return f"{run_url}/job/{job['job_id']}" if job.get("job_id") else run_url


def format_cause(job: dict) -> str:
    return f" — {job['cause']}" if job.get("cause") else ""


def format_alert(data: dict) -> str:
    branch = data["branch"]
    jobs = data["jobs"]

    emoji, verb = OUTCOMES.get(data.get("outcome"), OUTCOMES["failure"])
    commit_info = format_commit_info(data)

    if len(jobs) == 1 and not jobs[0].get("tasks"):
        job = jobs[0]
        return f"{emoji} {job['name']} {verb} on `{branch}`{commit_info}{format_cause(job)} ({job_url(data, job)})"

    header = f"{emoji} CI {verb} on `{branch}`{commit_info}"
    return fit_job_blocks(header, [format_job_block(data, job) for job in jobs], data.get("summary_url"))


def format_job_block(data: dict, job: dict) -> str:
    tasks = job.get("tasks") or []
    if not tasks:
        return f"• {job['name']}{format_cause(job)}: {job_url(data, job)}"
    lines = [f"• {job['name']}{format_cause(job)}"]
    lines.extend(format_task_lines(tasks))
    lines.append(f"  {job_url(data, job)}")
    return "\n".join(lines)


def fit_job_blocks(header: str, blocks: list, summary_url) -> str:
    text = "\n".join([header] + blocks)
    if len(text) <= MAX_ALERT_CHARS:
        return text
    kept = []
    for shown, block in enumerate(blocks):
        hidden = len(blocks) - shown
        more = f"+{hidden} more job{'s' if hidden > 1 else ''}" + (f": {summary_url}" if summary_url else "")
        if len("\n".join([header] + kept + [block, more])) > MAX_ALERT_CHARS:
            return "\n".join([header] + kept + [more])
        kept.append(block)
    return text


def markdown_cell(text: str) -> str:
    return text.replace("|", "\\|")


def format_summary(data: dict) -> str:
    _, verb = OUTCOMES.get(data.get("outcome"), OUTCOMES["failure"])
    lines = [
        f"### CI {verb} on `{data['branch']}`{format_commit_info(data)}",
        "",
        "| Job | Cause | Failed tasks |",
        "| --- | --- | --- |",
    ]
    for job in data["jobs"]:
        tasks = ", ".join(f"`{task}`" for task in job.get("tasks") or []) or "—"
        cause = markdown_cell(job.get("cause") or "—")
        lines.append(f"| [{markdown_cell(job['name'])}]({job_url(data, job)}) | {cause} | {tasks} |")
    return "\n".join(lines)


def main(argv=None):
    summary = "--summary" in (sys.argv[1:] if argv is None else argv)
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

    print(format_summary(data) if summary else format_alert(data))
    return 0


if __name__ == "__main__":
    sys.exit(main())
