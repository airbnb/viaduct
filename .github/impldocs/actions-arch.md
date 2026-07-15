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

- Must not send notifications. Atomics do not know whether they were launched by a human (who is watching) or by an orchestrator (which will handle notifications). Notification logic belongs exclusively in orchestrators.

- Must be manually dispatchable via `workflow_dispatch`. Every atomic should be independently runnable by hand for debugging and validation.

- Should accept `workflow_call` so orchestrators can compose them.

**Current atomics:**

| Workflow | Purpose | Runs on |
|---|---|---|
| `build-and-test.yml` | Full build + test matrix, detekt, ktlint, coverage verification | PR, merge to main, daily schedule, manual |
| `standalone-demoapp-tests.yml` | Publish to Maven Local, test all demoapps against local artifacts | PR, merge to main, daily schedule, manual |
| `bcv_api_check.yaml` | Binary API compatibility check | PR, merge to main, daily schedule, manual |
| `conventional-commit.yml` | Validate PR titles follow conventional commit format | PR, manual |

The first three are composed by `ci-manual-trigger.yml` — they don't have direct `push` or `pull_request` triggers themselves. `conventional-commit.yml` runs independently on PRs via its own `pull_request` trigger; it exposes `workflow_call` and `run_id` for consistency with the atomic convention, but no orchestrator composes it today.

### Orchestrator Workflows

An orchestrator composes atomics (and possibly inline jobs) into a higher-level automation flow. It should contain little or no domain logic that really belongs in an atomic.

**Rules for orchestrators:**

- Own all notification decisions. When an orchestrator detects that a child atomic failed, it formats an alert and routes it through the alert-posting helper (see [Notification Architecture](#notification-architecture)).

- Must be manually dispatchable via `workflow_dispatch`. When launched manually, notifications are **never** sent. Notifications are reserved for automatic triggers (`push`, `schedule`, or `workflow_call` with `send_alerts: true`). This prevents noise when a human is already watching the run.

- Not every orchestrator must notify. The rule is that only orchestrators *may* notify, and only on automatic triggers.

**Current orchestrators:**

| Workflow | Purpose | Runs on |
|---|---|---|
| `ci-manual-trigger.yml` | Full CI suite: build-and-test + standalone-demoapp-tests + API compat check | PR, merge to main, daily schedule (via periodic-green-check), manual |
| `ci-trigger.yml` | Full CI suite: build-and-test + demoapps-ci-check + API compat check | manual, workflow_call (push/PR triggers to be added when `ci-manual-trigger.yml` is deleted) |
| `demoapps-nightly-check.yml` | End-to-end snapshot validation: publish → push → wait → verify → cleanup | manual, workflow_call |
| `nightly-build.yml` | Nightly cron wrapper, delegates to `demoapps-nightly-check.yml` | weekday schedule (6am UTC), manual |
| `periodic-green-check.yml` | Scheduled CI health check + branch staleness detection | daily schedule, manual |

### Orchestrator Helpers

An orchestrator helper is a reusable workflow that orchestrators delegate to for a specific cross-cutting concern. Helpers are not atomics (they don't represent a domain function) and they're not orchestrators (they don't compose atomics). They exist to centralize shared infrastructure that multiple orchestrators need.

| Workflow | Purpose | Runs on |
|---|---|---|
| `post-alerts.yml` | Post pre-formatted alert text to Slack and Discord | called by orchestrators on failure, manual (test mode) |

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
| `standalone-demoapp-tests.yml` | `validate-inputs` |
| `bcv_api_check.yaml` | `api-compatibility` |
| `conventional-commit.yml` | `validate-pr-title` |

## Notification Architecture

### Principles

- **Notifications link to diagnostic info; they do not contain it.** An alert message names the failed job and links to its run. It does not reproduce logs, stack traces, or error details.

- **Only orchestrators send notifications.** Atomics never notify. This avoids double-alerting and keeps notification policy in one place per automation flow.

- **Manual runs never notify.** When a human triggers `workflow_dispatch`, they are watching. Alerts would be noise. Notifications fire only on automatic triggers: `push` events, `schedule` events, or `workflow_call` with explicit `send_alerts: true`.

### Alert Formatting

All alerts are formatted by `.github/scripts/format_alert.py`, a pure Python script that reads JSON from stdin and writes formatted text to stdout.

**Input schema:**

```json
{
  "branch": "main",
  "server_url": "https://github.com",
  "repository": "org/repo",
  "jobs": [
    { "name": "Build and Test", "run_id": "12345" },
    { "name": "API Compatibility", "run_id": "12346" }
  ],
  "sha": "abc1234...",
  "actor": "username"
}
```

- `branch`, `server_url`, `repository`, `jobs` are required.
- `sha`, `actor` are optional (present for push-triggered failures).
- `jobs` is a non-empty array. Each entry has a `name` (display label) and `run_id` (used to construct the URL `{server_url}/{repository}/actions/runs/{run_id}`).

**Output format:**

- Single failed job: one-line message with job name, branch, optional commit info, and link.
- Multiple failed jobs: header line followed by a bulleted list of `name: url` entries.

### Alert Posting Protocol

Orchestrators do not post to Slack or Discord directly. Instead they:

1. **Format** the alert text by piping JSON through `format_alert.py`. The JSON is constructed safely using `jq -n --arg` to prevent injection.
2. **Set** the text as a job output. Multi-line output (from multi-job alerts) requires heredoc syntax in `$GITHUB_OUTPUT`.
3. **Call** `post-alerts.yml` via `workflow_call` with the `text` input.

`post-alerts.yml` is the sole workflow that holds Slack and Discord credentials. Its `post-call` job posts the text to both Slack (`chat.postMessage` API with `SLACK_BOT_TOKEN`) and Discord (webhook with `DISCORD_CI_WEBHOOK_URL`). Callers pass `secrets: inherit`.

The orchestrator pattern in YAML:

```yaml
format-alerts:
  needs: [child-a, child-b]
  runs-on: ubuntu-latest
  if: >-
    always()
    && (needs.child-a.result == 'failure' || needs.child-b.result == 'failure')
    && (github.event_name == 'push' || inputs.send_alerts == true)
  outputs:
    text: ${{ steps.fmt.outputs.text }}
  steps:
    - uses: actions/checkout@v6
    - name: Format alert
      id: fmt
      run: |
        # Build jobs array from failed children using jq
        # Pipe to format_alert.py
        # Write text to $GITHUB_OUTPUT using heredoc
      shell: bash

send-alerts:
  needs: [format-alerts]
  if: always() && needs.format-alerts.result == 'success'
  uses: ./.github/workflows/post-alerts.yml
  with:
    text: ${{ needs.format-alerts.outputs.text }}
  secrets: inherit
```

## Workflow Diagrams

### Push / PR to `main`

```
push/PR to main
  |
  v
ci-manual-trigger.yml  [orchestrator]
  |
  |--- build-and-test.yml  [atomic]
  |      validate-inputs --> test   (self-contained: compiles + runs tests, no build dep)
  |                     '--> build --> detekt
  |                                --> ktlint
  |                                --> coverage-verification
  |
  |--- standalone-demoapp-tests.yml  [atomic]
  |      validate-inputs --> publish-to-maven-local --> test-demoapps
  |                                                --> test-starwars
  |
  |--- bcv_api_check.yaml  [atomic]
  |      api-compatibility
  |
  '--- [on push, if any atomic failed]
         format-alerts --> send-alerts --> post-alerts.yml [helper] --> Slack + Discord
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
  |    ci-manual-trigger.yml  [orchestrator, send_alerts: true]
  |      |
  |      |--- build-and-test.yml  [atomic]
  |      |--- standalone-demoapp-tests.yml  [atomic]
  |      |--- bcv_api_check.yaml  [atomic]
  |      |
  |      '--- [if any atomic failed]
  |             format-alerts --> send-alerts --> post-alerts.yml [helper] --> Slack + Discord
  |
  '--- staleness-check  [inline job]
         |
         '-- [if stale]
               format-alert --> send-staleness-alert --> post-alerts.yml [helper] --> Slack + Discord
```

### Nightly Build (6am UTC weekdays)

```
schedule / manual dispatch
  |
  v
nightly-build.yml  [orchestrator, thin wrapper]
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
```

### CI Check (via `ci-trigger.yml`)

```
manual dispatch / workflow_call
  |
  v
ci-trigger.yml  [orchestrator]
  |
  |--- build-and-test.yml  [atomic]
  |--- demoapps-ci-check.yml  [atomic]
  |--- bcv-api-check.yml  [atomic]
  |
  '--- [if any atomic failed && send_alerts]
         format-alerts --> send-alerts --> post-alerts.yml [helper] --> Slack + Discord
```

### Manual Dispatch

Every workflow supports `workflow_dispatch` so it can be run by hand independently. This serves two purposes: **testing** (validate a workflow change on a branch before merging) and **on-demand execution** (run a check or suite without waiting for its automatic trigger).

Notifications are suppressed on manual dispatch — the person who triggered the run is already watching.

| Workflow | What you'd run it for | Key inputs |
|---|---|---|
| `build-and-test.yml` | Test a specific OS/Java combination | `os`, `java_versions` |
| `standalone-demoapp-tests.yml` | Test demoapps against a specific OS/Java combination | `os`, `java_versions` |
| `bcv_api_check.yaml` | Check API compatibility on a branch | — |
| `ci-manual-trigger.yml` | Run the full CI suite on demand (legacy) | `send_alerts` (default: off) |
| `ci-trigger.yml` | Run the full CI suite using new atomics | `send_alerts` (default: off) |
| `demoapps-nightly-check.yml` | Run the end-to-end snapshot validation loop | `ref`, `run_ci_check` (default: off) |
| `nightly-build.yml` | Trigger the nightly validation without waiting for cron | — |
| `periodic-green-check.yml` | Run scheduled checks without waiting for cron | `branch`, `mode` (ci-check / staleness-check / all) |
| `post-alerts.yml` | Verify Slack and Discord connectivity | `mode: test-posts` |
| `conventional-commit.yml` | Test the PR title validator itself | — |
| `release.yml` | Publish either a public release candidate or the final release (see [.github/impldocs/release-runbook.md](release-runbook.md)) | `release_version`, `rc_ver`, `final`, `release_notes` |

## Appendix: Future Work

### Workflow renames

Move logic from current workflows to their renamed replacements, then delete the originals:

- `ci-manual-trigger.yml` --> `ci-trigger.yml`

An empty stub for the new name already exists.

### Explicit permissions and concurrency

Not all workflows currently declare explicit `permissions:` blocks or `concurrency` groups. These should be added incrementally:

- **Permissions:** set `permissions:` explicitly in every workflow, defaulting to read-only. Elevate only the specific jobs that need write access (e.g., a publication job needs `contents: write`; CI jobs do not). Each workflow should document which secrets it requires so callers know what `secrets: inherit` actually grants.

- **Concurrency:** workflows that mutate shared state (publish artifacts, push branches) need `concurrency` groups keyed to the branch or publication target. Use `cancel-in-progress: true` only when the old run's results are genuinely stale and incomplete work is harmless — CI checks qualify; a half-finished publication does not.

### Build scan infrastructure — DONE

The custom build scan artifact pipeline has been removed (Slice B). Build scan URLs are now surfaced automatically via `$GITHUB_STEP_SUMMARY` by `gradle/actions/setup-gradle@v5`.

- Deleted `post-build-scan-comments.yml`, `post_build_scan_comments.py`, `maybe_capture_build_scan_artifact.py`, `extract_build_scan_url.py` and their tests
- Removed `build-scan-artifact.json` upload steps from `build-and-test.yml`
- Deleted `run_gradle_with_build_scan_capture.sh` and inlined Gradle invocations directly into `build-and-test.yml`
