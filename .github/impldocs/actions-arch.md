# GitHub Actions Architecture

This document describes the architectural principles, workflow taxonomy, and notification system for Viaduct's GitHub Actions CI infrastructure.

## Boundaries

- **GitHub Actions** own orchestration against a pushed branch state.
- **Gradle tasks** own repo mutations the release manager may need to run locally.
- **Python** stays pure: derive names, validate inputs, normalize versions, emit JSON or step outputs. Python may not publish, push branches, mutate repo files, or invoke side-effectful tools.

## Design Rules

1. **No side-effectful Python.** Python scripts may validate inputs, derive versions, normalize strings, and generate manifests. They may not publish, push, or mutate.

2. **No release-manager mutation through remote Actions.** If a step changes `VERSION`, demoapp `gradle.properties`, or release-branch history, it should be a local Gradle task the release manager runs and commits.

3. **Cloud workflows only operate on pushed state.** They may run CI, publish snapshots/releases, wait for repository visibility, push to demoapp repos via Copybara, and validate standalone repos.

4. **Two-layer workflow architecture: atomics and orchestrators.** See [Workflow Taxonomy](#workflow-taxonomy) below.

5. **Caching is an accelerator, not a handoff.** The `gradle/actions/setup-gradle` cache (the `~/.gradle` GitHub Actions cache) is a best-effort, cross-run speed optimization — not a deterministic channel for passing build outputs between jobs. Every job must succeed on a cold cache with no restored state; a cache hit or miss may change speed but must never change correctness. Any output one job genuinely requires from another must be transferred explicitly — via `actions/upload-artifact`/`download-artifact` or a remote build cache — never by assuming a job will restore another job's local Gradle cache. (Restoring a shared cache is also a correctness *hazard*: a cancelled job can save a partially-written cache that later jobs restore — see `cancel-in-progress` note under [Explicit permissions and concurrency](#explicit-permissions-and-concurrency).)

## Workflow Taxonomy

Every workflow is classified as an **atomic**, an **orchestrator**, or an **orchestrator helper**.

### Atomic Workflows

An atomic workflow performs one well-defined, coherent, loosely-coupled function. It may have internal orchestration (multiple jobs, matrix strategies), but externally it presents a single capability.

**Rules for atomics:**

- Must expose a `run_id` workflow output, always, unconditionally. This allows orchestrators to construct direct URLs to the atomic's run. The `run_id` is emitted early in the workflow (before any step that might fail) so it is available even when the workflow fails.

- Must not send notifications. Atomics do not know whether they were launched by a human who is watching. Notification logic belongs to the failure listener (see [Notification Architecture](#notification-architecture)).

- Must be manually dispatchable via `workflow_dispatch`. Every atomic should be independently runnable by hand for debugging and validation.

- Should accept `workflow_call` so orchestrators can compose them.

**Current atomics:**

| Workflow | Purpose | Runs on |
|---|---|---|
| `build-and-test.yml` | Full build + test matrix, detekt, ktlint, coverage verification | PR, merge to main, daily schedule, manual |
| `demoapps-ci-check.yml` | Publish to Maven Local, test all demoapps against local artifacts | PR, merge to main, daily schedule, manual |
| `bcv-api-check.yml` | Binary API compatibility check | PR, merge to main, daily schedule, manual |
| `conventional-commit.yml` | Validate PR titles follow conventional commit format | PR, manual |

The first three are composed by `ci-trigger.yml`, which owns the `push`/`pull_request` triggers — the atomics have no direct `push` or `pull_request` triggers themselves. `conventional-commit.yml` runs independently on PRs via its own `pull_request` trigger; it exposes `workflow_call` and `run_id` for consistency with the atomic convention, but no orchestrator composes it today.

### Orchestrator Workflows

An orchestrator composes atomics (and possibly inline jobs) into a higher-level automation flow. It should contain little or no domain logic that really belongs in an atomic.

**Rules for orchestrators:**

- Must not send failure notifications either. A run's failure alerting belongs to `ci-retry-then-alert.yml`, which observes completed runs from outside them (see [Notification Architecture](#notification-architecture)). An orchestrator may still alert on a condition only it can detect, as `periodic-green-check.yml` does for branch staleness.

- Must be manually dispatchable via `workflow_dispatch`. A manual dispatch does not alert, because whoever triggered it is watching.

**Current orchestrators:**

| Workflow | Purpose | Runs on |
|---|---|---|
| `ci-trigger.yml` | Full CI suite: build-and-test + demoapps-ci-check + bcv-api-check | push/PR to main, daily schedule (via periodic-green-check), manual, workflow_call |
| `demoapps-nightly-check.yml` | End-to-end snapshot validation: publish → push → wait → verify → cleanup | manual, workflow_call |
| `nightly-build.yml` | Nightly cron wrapper, delegates to `demoapps-nightly-check.yml` | weekday schedule (6am UTC), manual |
| `periodic-green-check.yml` | Scheduled CI health check + branch staleness detection | daily schedule, manual |

### Orchestrator Helpers

An orchestrator helper is a reusable workflow that orchestrators delegate to for a specific cross-cutting concern. Helpers are not atomics (they don't represent a domain function) and they're not orchestrators (they don't compose atomics). They exist to centralize shared infrastructure that multiple orchestrators need.

| Workflow | Purpose | Runs on |
|---|---|---|
| `post-alerts.yml` | Post pre-formatted alert text to Slack and Discord | called by listeners and orchestrators on failure, manual (test mode) |

### Listener Workflows

A listener runs on `workflow_run`, after another run completes. It sees that run's conclusion and attempt number, which no job inside the run can observe.

| Workflow | Purpose | Runs on |
|---|---|---|
| `ci-retry-then-alert.yml` | Retry a failed CI run once, then alert if the retry also fails | completion of CI Check, Nightly Build, Periodic Green Check |
| `ci-watchdog.yml` | Alert on startup failures, which stop in-run jobs from running at all | completion of CI Check, Nightly Build, Periodic Green Check, Release |

`ci-retry-then-alert.yml` holds `actions: write`, the only such grant in the repo, because re-running a run requires it. It is a sibling of `ci-watchdog.yml` rather than a job inside it so that a broken retry cannot take the watchdog down with it.

### Release Workflows

`release.yml` is separate from the CI infrastructure described above. It is a manually-triggered workflow used by release managers to publish either a release candidate or a final Viaduct release. See [.github/impldocs/release-runbook.md](release-runbook.md) for the full release process.

- RC mode publishes `X.Y.Z-rc.N` artifacts to Maven Central and the Gradle Plugin Portal, pushes demo apps to `rc/vX.Y.Z-rc.N`, and verifies the published RC.
- Final mode publishes the final `X.Y.Z` release, pushes demo apps to `main`, verifies them, creates the `vX.Y.Z` tag, and publishes the GitHub release.

`release.yml` delegates the actual artifact publication work to `publish-branch.yml`.

## run_id Output Convention

Every atomic workflow exposes a `run_id` output so orchestrators can construct direct links to child runs. The pattern:

**Workflow-level** (under `on.workflow_call.outputs`):

```yaml
workflow_call:
  outputs:
    run_id:
      description: "GitHub Actions run ID"
      value: ${{ jobs.<first-job>.outputs.run_id }}
```

**Job-level** (in the first job, before any failable step):

```yaml
jobs:
  <first-job>:
    outputs:
      run_id: ${{ steps.emit.outputs.run_id }}
    steps:
      - name: Emit run ID
        id: emit
        run: echo "run_id=${{ github.run_id }}" >> "$GITHUB_OUTPUT"
        shell: bash
      # ... remaining steps follow
```

The emit step runs early so that `run_id` is available to the calling orchestrator even if later steps fail. GitHub Actions makes outputs from failed `needs` jobs available when the dependent job uses `if: always()`.

**Current run_id sources:**

| Atomic | Job that emits run_id |
|---|---|
| `build-and-test.yml` | `validate-inputs` |
| `demoapps-ci-check.yml` | `validate-inputs` |
| `bcv-api-check.yml` | `api-compatibility` |
| `conventional-commit.yml` | `validate-pr-title` |

## Notification Architecture

### Principles

- **Notifications link to diagnostic info; they do not contain it.** An alert message names the failed job and links to its run. It does not reproduce logs, stack traces, or error details.

- **One listener owns failure alerting.** `ci-retry-then-alert.yml` is the only place a run's failure becomes an alert. Neither atomics nor orchestrators notify on failure. A second alerting path anywhere means double-alerting.

- **Manual runs never notify.** When a human triggers `workflow_dispatch`, they are watching. The listener therefore alerts only for `push` and `schedule` runs, and only on `main`.

- **Transient failures are retried before they alert.** CI Check and Periodic Green Check are re-run once on their first failure and alert only if the retry also fails. Nightly Build is not retried: its failures have been real defects, and it is the only Windows signal, so delaying that alert by a full run buys nothing.

- **A retry that succeeds is still reported.** A run that ends `success` past its first attempt gets an informational notice naming what failed on attempt 1. Silence would hide the flake rate, which is the number that decides whether a flake is worth chasing. Re-running an already-green run reports nothing, since attempt 1 has no failed job to name.

### Alert Formatting

All alerts are formatted by `.github/scripts/format_alert.py`, a pure Python script that reads JSON from stdin and writes formatted text to stdout.

**Input schema:**

```json
{
  "branch": "main",
  "server_url": "https://github.com",
  "repository": "org/repo",
  "jobs": [
    { "name": "Build and Test", "run_id": "12345", "tasks": [":core:tenant:runtime:compileTestKotlin"] },
    { "name": "API Compatibility", "run_id": "12346" }
  ],
  "sha": "abc1234...",
  "actor": "username",
  "attempt": 2,
  "outcome": "retry_success"
}
```

- `branch`, `server_url`, `repository`, `jobs` are required.
- `sha`, `actor` are optional (present for push-triggered failures).
- `attempt` is optional and rendered only above 1, so the label means the run had already been retried.
- `outcome` is optional, either `failure` (the default) or `retry_success`. It selects the emoji and verb. An unrecognized value is rejected rather than silently read as a failure.
- `jobs` is a non-empty array. Each entry has a `name` (display label), a `run_id` (used to construct the URL `{server_url}/{repository}/actions/runs/{run_id}`), and an optional `tasks` array of failing Gradle task paths.

**Output format:**

- One failed job with no tasks: a single line with job name, branch, optional commit info, and link.
- Otherwise a header line followed by one bullet per job. A job with no tasks stays inline as `name: url`; a job with tasks puts its name, then up to 3 tasks one per line, then its link. Beyond 3 the last line gains `+N more`.

Job names come from the run's job list, so an alert names the job that actually failed (`build-and-test / Test (Java 17) ubuntu-latest`) rather than the atomic that contained it. Tasks come from `extract_failed_tasks.py` reading that job's log, which is why a failure with no Gradle task — an HTTP 429 from a dependency repository, say — still reports its job name.

### Alert Posting Protocol

Nothing posts to Slack or Discord directly. Instead:

1. **Format** the alert text with `.github/actions/collect-failure-info`, which pipes JSON through `format_alert.py`. The JSON is constructed with `jq -n --arg` to prevent injection.
2. **Set** the text as a job output. Multi-line output (from multi-job alerts) requires heredoc syntax in `$GITHUB_OUTPUT`, which the composite action handles.
3. **Call** `post-alerts.yml` via `workflow_call` with the `text` input.

`post-alerts.yml` is the sole workflow that holds Slack and Discord credentials. Its `post-call` job posts the text to both Slack (`chat.postMessage` API with `SLACK_BOT_TOKEN`) and Discord (webhook with `DISCORD_CI_WEBHOOK_URL`). Callers pass `secrets: inherit`.

The listener pattern in YAML:

```yaml
triage:
  runs-on: ubuntu-latest
  if: >-
    github.event.workflow_run.head_branch == 'main'
    && contains(fromJSON('["push", "schedule"]'), github.event.workflow_run.event)
    && (github.event.workflow_run.conclusion == 'failure'
        || (github.event.workflow_run.conclusion == 'success'
            && github.event.workflow_run.run_attempt > 1))
  outputs:
    text: ${{ steps.fmt.outputs.text }}
  steps:
    - uses: actions/checkout@v6
    - name: Retry once before alerting
      id: retry
      if: <failure> && github.event.workflow_run.run_attempt == 1 && <retry-eligible workflow>
      run: |
        # POST rerun-failed-jobs; set retried=true, or false if the call fails
    - name: Collect the jobs and tasks that failed
      id: jobs
      if: "!cancelled() && steps.retry.outputs.retried != 'true'"
      run: |
        # query attempt 1 when the run ended in success, else the current attempt
        # list failed job ids and names, pipe each job's log through extract_failed_tasks.py
        # emit outcome=retry_success|failure, and an empty jobs_json when there is nothing to say
    - name: Format alert
      id: fmt
      if: "!cancelled() && steps.jobs.outputs.jobs_json != ''"
      uses: ./.github/actions/collect-failure-info

post:
  needs: [triage]
  if: always() && needs.triage.outputs.text != ''
  uses: ./.github/workflows/post-alerts.yml
  with:
    text: ${{ needs.triage.outputs.text }}
  secrets: inherit
```

Empty text is how a retried run stays quiet, so `post` keys off the text rather than `triage`'s result. A skipped retry step leaves `retried` unset, which also alerts. If the re-run call fails, or a job's log cannot be read, the listener still alerts, because going silent is worse than one extra alert or one missing task name.

Only the collection step knows whether the run recovered, so it emits `outcome` rather than having the format step recompute it.

## Workflow Diagrams

### Push / PR to `main`

```
push/PR to main
  |
  v
ci-trigger.yml  [orchestrator]
  |
  |--- build-and-test.yml  [atomic]
  |      validate-inputs --> test   (self-contained: compiles + runs tests, no build dep)
  |                     '--> build --> detekt
  |                                --> ktlint
  |                                --> coverage-verification
  |
  |--- demoapps-ci-check.yml  [atomic]
  |      validate-inputs --> publish-to-maven-local --> test-starters
  |                                                --> test-starwars
  |                                                --> test-kotlin-matrix
  |                                                --> test-gradle-matrix
  |
  '--- bcv-api-check.yml  [atomic]
         api-compatibility

[on push, once the run completes with conclusion=failure]
  |
  v
ci-retry-then-alert.yml  [listener]
  |
  |--- failure, attempt 1 --> rerun-failed-jobs, no message
  |--- failure, attempt 2 --> post-alerts.yml [helper] --> Slack + Discord
  '--- success, attempt 2 --> post-alerts.yml [helper] --> Slack + Discord
                              (informational; names what failed on attempt 1)
```

### Daily Schedule (2pm UTC)

```
schedule
  |
  v
periodic-green-check.yml  [orchestrator]
  |
  |--- ci-check
  |      |
  |      v
  |    ci-trigger.yml  [orchestrator]
  |      |
  |      |--- build-and-test.yml  [atomic]
  |      |--- demoapps-ci-check.yml  [atomic]
  |      '--- bcv-api-check.yml  [atomic]
  |
  '--- staleness-check  [inline job]
         |
         '-- [if stale]
               format-alert --> send-staleness-alert --> post-alerts.yml [helper] --> Slack + Discord

[once the run completes with conclusion=failure]
  |
  v
ci-retry-then-alert.yml  [listener]   (retried once, like CI Check)
```

### Nightly Build (6am UTC weekdays)

```
schedule / manual dispatch
  |
  v
nightly-build.yml  [orchestrator, thin wrapper]
  |
  |--- windows-check  [inline job, matrix: Java 17, 21]
  |      the only place the unscoped root `check` runs on Windows
  |
  |--- demoapps-standalone-windows  [inline job, matrix: Java 17, 21]
  |
  v
demoapps-nightly-check.yml  [orchestrator]
  |
  |--- ci-precheck (if run_ci_check)
  |      v
  |    demoapps-ci-check.yml  [atomic]
  |
  |--- publish-snapshot
  |      v
  |    publish-branch.yml  [atomic, mode=snapshot]
  |
  |--- push-demoapps (parallel)       wait-for-publication (parallel)
  |      v                               v
  |    push-demoapps.yml  [atomic]     (poll Sonatype until 200)
  |
  |--- check-demoapps
  |      v
  |    check-published-demoapps.yml  [atomic]
  |
  '--- cleanup (if tmp/* branch)
         delete tmp branches from viaduct-dev/* repos

[once the run completes with conclusion=failure]
  |
  v
ci-retry-then-alert.yml  [listener]
  '--- post-alerts.yml [helper] --> Slack + Discord   (no retry; alerts on the first failure)
```

The listener watches `nightly-build.yml`, so the Windows jobs are covered without any alerting inside the run. That is what they previously lacked: alerting used to live in `demoapps-nightly-check.yml` and was gated on its own four jobs, so a nightly that failed only on Windows was silent.

### CI Check (via `ci-trigger.yml`)

```
manual dispatch / workflow_call
  |
  v
ci-trigger.yml  [orchestrator]
  |
  |--- build-and-test.yml  [atomic]
  |--- demoapps-ci-check.yml  [atomic]
  '--- bcv-api-check.yml  [atomic]

A manual dispatch does not alert, because the listener only handles `push` and `schedule` runs.
```

### Manual Dispatch

Every workflow supports `workflow_dispatch` so it can be run by hand independently. This serves two purposes: **testing** (validate a workflow change on a branch before merging) and **on-demand execution** (run a check or suite without waiting for its automatic trigger).

Notifications are suppressed on manual dispatch, because the person who triggered the run is already watching.

| Workflow | What you'd run it for | Key inputs |
|---|---|---|
| `build-and-test.yml` | Test a specific OS/Java combination | `os`, `java_versions` |
| `demoapps-ci-check.yml` | Test demoapps against a specific OS/Java combination | `os`, `java_versions` |
| `bcv-api-check.yml` | Check API compatibility on a branch | — |
| `ci-trigger.yml` | Run the full CI suite on demand | — |
| `demoapps-nightly-check.yml` | Run the end-to-end snapshot validation loop | `ref`, `run_ci_check` (default: off) |
| `nightly-build.yml` | Trigger the nightly validation without waiting for cron | — |
| `periodic-green-check.yml` | Run scheduled checks without waiting for cron | `branch`, `mode` (ci-check / staleness-check / all) |
| `post-alerts.yml` | Verify Slack and Discord connectivity | `mode: test-posts` |
| `conventional-commit.yml` | Test the PR title validator itself | — |
| `release.yml` | Publish either a public release candidate or the final release (see [.github/impldocs/release-runbook.md](release-runbook.md)) | `release_version`, `rc_ver`, `final`, `release_notes` |

## Appendix: Future Work

### Workflow renames — DONE

`ci-manual-trigger.yml` has been replaced by `ci-trigger.yml`, which now owns the `push`/`pull_request` triggers and composes the CI atomics (`build-and-test.yml`, `demoapps-ci-check.yml`, `bcv-api-check.yml`). The old workflow has been deleted.

### Explicit permissions and concurrency

Not all workflows currently declare explicit `permissions:` blocks or `concurrency` groups. These should be added incrementally:

- **Permissions:** set `permissions:` explicitly in every workflow, defaulting to read-only. Elevate only the specific jobs that need write access (e.g., a publication job needs `contents: write`; CI jobs do not). Each workflow should document which secrets it requires so callers know what `secrets: inherit` actually grants.

- **Concurrency:** workflows that mutate shared state — publish artifacts, push branches, **or write the shared `~/.gradle` build cache** — need `concurrency` groups keyed to the branch or target. Use `cancel-in-progress: true` only when interrupting the old run cannot corrupt that shared state. CI build/test runs on `main` do **not** qualify: they write the shared cache, and a run cancelled mid cache-save can leave a partial entry that a later run restores — a known cache-poisoning hazard (gradle/actions#72). `build-and-test.yml`, `demoapps-ci-check.yml`, and `bcv-api-check.yml` therefore set `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}` — PRs fast-cancel superseded runs, `main` runs queue and complete. A half-finished publication likewise does not qualify.

### Build scan infrastructure — DONE

The custom build scan artifact pipeline has been removed (Slice B). Build scan URLs are now surfaced automatically via `$GITHUB_STEP_SUMMARY` by `gradle/actions/setup-gradle@v5`.

- Deleted `post-build-scan-comments.yml`, `post_build_scan_comments.py`, `maybe_capture_build_scan_artifact.py`, `extract_build_scan_url.py` and their tests
- Removed `build-scan-artifact.json` upload steps from `build-and-test.yml`
- Deleted `run_gradle_with_build_scan_capture.sh` and inlined Gradle invocations directly into `build-and-test.yml`
