# Running A Full Publication Test

## Overview

A full publication test publishes a snapshot release of Viaduct to Maven Central, pushes snapshot copies of the demo apps to their standalone repositories at `github.com/viaduct-dev`, and verifies that the demo apps build against the published artifacts. This is the most complete test of the end-to-end build, packaging, and publication tooling.

The process has the following steps:

1. Create a snapshot branch and push it to `origin`
2. Run the e2e-test orchestration workflow (publishes artifacts, pushes demo apps, verifies them)
3. Fix and iterate if tests fail
4. Clean up

## Prerequisites

- Permission to create branches in `https://github.com/airbnb/viaduct` (the `origin` remote)
- Local clone of the Viaduct repo with two remotes: `origin` pointing to `airbnb/viaduct` and `fork` pointing to your personal fork (see RELEASE-RUNBOOK.md for setup)
- `gh` CLI installed and authenticated

## Detailed Steps

These instructions assume you are working on a local branch (typically tracking a branch on your `fork` remote) and want to run an end-to-end publication test against it.

### 1. Create a snapshot branch

From your working branch, create a new local branch for the e2e test. By convention, name it `<your-github-username>/SOMETHING` — the username prefix avoids conflicts with other contributors.

```bash
git checkout -b rstata/e2e-my-feature
```

Run `bumpSnapshotVersion` to ensure VERSION ends in `-SNAPSHOT` and add a unique `-rc.XXXX` identifier (this avoids collisions with other snapshot tests on Maven Central):

```bash
./gradlew bumpSnapshotVersion
```

Commit and push to `origin` (not your fork — the CI workflows and publication secrets live in `airbnb/viaduct`):

```bash
export REF=$(git branch --show-current) && \
  git add VERSION demoapps/*/gradle.properties && \
  git commit -m "chore: snapshot for e2e testing" && \
  git push origin $REF
```

### 2. Run the e2e test workflow

Trigger the orchestration workflow, which publishes snapshot artifacts, pushes demo apps to their standalone repos, waits for Maven Central propagation, and verifies the demo apps build:

```bash
gh workflow run e2e-snapshot-test.yml \
  --repo airbnb/viaduct \
  -f ref=$REF
```

Monitor progress:

```bash
gh run list --workflow=e2e-snapshot-test.yml --repo airbnb/viaduct --limit 3
```

The workflow runs these steps in sequence:

1. **publish-branch** — validates version state, runs checks, publishes SNAPSHOT artifacts to Maven Central
2. **push-demoapps** — copies demo apps to `tmp/$REF` branches in `viaduct-dev/*` repos via Copybara
3. **wait-for-publication** — polls Maven Central until the published artifacts are resolvable
4. **check-published-demoapps** — clones each standalone demo app repo and runs `./gradlew test` against the published artifacts

**Note on step 3 (wait-for-publication):** The built-in polling for Maven Central propagation can be unreliable and will eventually time out. Propagation typically takes 5-15 minutes but can occasionally take longer. If the verify step fails with "Could not find" dependency errors, wait a few minutes and re-run just the verification:

```bash
gh workflow run check-published-demoapps.yml \
  --repo airbnb/viaduct \
  -f ref=tmp/$REF
```

### 3. Fix and iterate

If tests fail for reasons other than propagation delay, fix the issue on your **working branch** (the branch you were on before creating the e2e test branch), not on the e2e test branch itself. Then merge the fix into the e2e test branch and re-push:

```bash
# Switch to your working branch and make fixes
git checkout my-feature-branch
# ... fix the issue, commit ...

# Merge fixes into the e2e test branch
git checkout $REF
git merge my-feature-branch
git push origin $REF
```

Since the only changes unique to the e2e test branch are the version bump (VERSION + demo app gradle.properties), merges from your working branch should not produce conflicts.

Then re-run the e2e test workflow from Step 2.

### 4. (Optional) Test a demo app locally

You can also pull a pushed demo app and test it locally. You'll need local clones of the standalone demo app repos:

- `https://github.com/viaduct-dev/cli-starter`
- `https://github.com/viaduct-dev/jetty-starter`
- `https://github.com/viaduct-dev/ktor-starter`
- `https://github.com/viaduct-dev/micronaut-starter`
- `https://github.com/viaduct-dev/starwars`

To test locally:

```bash
(cd starwars && \
  git fetch origin && \
  git checkout -B tmp/$REF origin/tmp/$REF && \
  USE_VIADUCT_SNAPSHOT_REPO=true ./gradlew test --refresh-dependencies)
```

The `USE_VIADUCT_SNAPSHOT_REPO=true` environment variable tells the demo app's build to include Maven Central's snapshot repository for dependency resolution. The `--refresh-dependencies` flag forces Gradle to re-fetch SNAPSHOT artifacts instead of using its local cache (which defaults to 24 hours for changing modules). This matters if you've re-published the same SNAPSHOT version after fixing an issue.

### 5. **IMPORTANT**: Clean up

When you're done testing, delete the e2e test branch from `origin` to avoid accumulating stale branches:

```bash
git push origin --delete $REF && \
  git checkout my-feature-branch && \
  git branch -D $REF
```

The `tmp/` branches pushed to the `viaduct-dev/*` demo app repos also need to be cleaned up:

```bash
for repo in cli-starter jetty-starter ktor-starter micronaut-starter starwars; do
  gh api -X DELETE "repos/viaduct-dev/${repo}/git/refs/heads/tmp/${REF}" 2>/dev/null && \
    echo "Deleted tmp/${REF} from viaduct-dev/${repo}" || \
    echo "No tmp/${REF} in viaduct-dev/${repo} (already deleted or never pushed)"
done
```
