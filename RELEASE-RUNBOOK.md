# Cutting a Viaduct Release

## Overview

The release process follows these steps:

1. **Bump version** on main to the next SNAPSHOT (e.g., `0.8.0-SNAPSHOT`)
2. **Create release branch** from the commit before the bump (e.g., `release/v0.7.0` with VERSION `0.7.0`)
3. **Validate** — run CI across all Java/OS combinations
4. **Generate changelog** and review with the team
5. **Confirm release** at the team meeting
6. **Publish** — one command that publishes artifacts, pushes demo apps, verifies them, creates the tag, and publishes the GitHub release
7. **Verify** — pull latest version of Star Wars demo app and verify it passes its tests

## Quick Command Reference

Set these shell variables before starting. `RELEASE_VER` is the version you are releasing this week. `NEXT_VER` is the version that will replace it on main.

```bash
export PREV_VER="0.28.0" \
export RELEASE_VER="0.29.0" \
export NEXT_VER="0.30.0" \
export GH_USER="your-github.com-username"
```

| Step | Command |
|------|---------|
| [2. Version bump](#2-bump-version-on-main) | Edit VERSION, `./gradlew syncDemoAppVersions`, PR |
| [3. Release branch](#3-create-release-branch) | `git checkout -b release/v${RELEASE_VER}`, set VERSION, push |
| [4. Validate build](#4-validate-build) | `gh workflow run ci-trigger.yml --ref release/v${RELEASE_VER}` |
| [5. Changelog](#5-generate-changelog) | `generate_changelog.py origin/release/v${PREV_VER} HEAD` |
| [7. Publish release](#7-publish-release) | `gh workflow run release.yml -f release_version=${RELEASE_VER} -F release_notes=changelog.md` |
| [8. Verify](#8-verify) | `cd ~/repos/starwars && git pull && ./gradlew test` |

## Prerequisites

Skip this section if you have already done a Viaduct release before. Come back here if any step below fails with an authentication or tooling error.

### Required Tools

```bash
# GitHub CLI
brew install gh

# Python 3 (usually pre-installed on macOS)
python3 --version

# uv — fast Python package runner (for the changelog script)
brew install uv
```

### Required Access

Verify you have access to all of these **before release day**:

- [ ] **GitHub:** You are a member of the [Airbnb GitHub organization](https://github.com/orgs/airbnb/people)
- [ ] **Viaduct-dev:** You are a member of the [viaduct-dev organization](https://github.com/orgs/viaduct-dev/people)
- [ ] **Gradle Plugin Portal:** You can log in to https://plugins.gradle.org/u/viaduct-maintainers
- [ ] **Sonatype:** You can find the `viaductbot` credentials in the shared vault

If you are missing access, contact your team lead before release day.

### One-Time Setup

**Step 1: Verify SSH access to GitHub**

```bash
ssh -T git@github.com
```

Expected output: `Hi <username>! You've successfully authenticated, but GitHub does not provide shell access.`

If this fails:
- Ensure you have SSH keys set up: https://docs.github.com/en/authentication/connecting-to-github-with-ssh
- Add your key to ssh-agent: `ssh-add ~/.ssh/id_rsa`

**Step 2: Authenticate GitHub CLI**

```bash
gh auth login
```

- Select: `GitHub.com`
- Select: `HTTPS`
- Authenticate via browser when prompted

**Step 3: Fork the public repo**

1. Open https://github.com/airbnb/viaduct in your browser
2. Click **Fork** (top right)
3. Select your personal GitHub account

This creates `github.com/${GH_USER}/viaduct`.

**Step 4: Clone the repo and set up remotes**

You only need one local clone with two remotes: `origin` for the public repo (release branches, workflows) and `fork` for your personal fork (version-bump PRs).

```bash
git clone git@github.com:airbnb/viaduct.git ~/repos/viaduct
cd ~/repos/viaduct
git remote add fork git@github.com:${GH_USER}/viaduct.git
gh repo set-default airbnb/viaduct

# Verify remotes
git remote -v
# Should show:
#   origin  git@github.com:airbnb/viaduct.git (fetch/push)
#   fork    git@github.com:${GH_USER}/viaduct.git (fetch/push)
```

## Versioning

The root `VERSION` file is the source of truth:

- **On main:** Always `X.Y.Z-SNAPSHOT` (e.g., `0.8.0-SNAPSHOT`)
- **On release branches:** Exactly `X.Y.Z` (e.g., `0.7.0`)

Demo apps have `gradle.properties` files with a `viaductVersion` property that **must match** the root VERSION. Use Gradle tasks to keep them in sync:

```bash
./gradlew syncDemoAppVersions     # Copy VERSION to all demo apps
./gradlew confirmDemoAppVersions  # Validate all demo apps match VERSION (CI runs this automatically)
```

## Detailed Release Process

During the team meeting, a release manager is selected for the week.

### 2) Bump version on main

> Start this on Tuesday or early Wednesday — the PR review + merge takes time.

**Sync your fork and create a branch:**

```bash
cd ~/repos/viaduct
git checkout main
git pull origin main
git push fork main
git checkout -b candidate/v${NEXT_VER}
```

**Edit VERSION and sync demo apps:**

```bash
echo "${NEXT_VER}-SNAPSHOT" > VERSION
./gradlew syncDemoAppVersions
```

**Verify the changes:**

```bash
git diff .
# Should show VERSION + 5 demoapps/*/gradle.properties changed
```

**Commit, push to your fork, and create PR:**

```bash
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Bump version to ${NEXT_VER}-SNAPSHOT"
git push fork candidate/v${NEXT_VER}

gh pr create \
  --repo airbnb/viaduct \
  --base main \
  --head ${GH_USER}:candidate/v${NEXT_VER} \
  --title "chore: Bump version to ${NEXT_VER}-SNAPSHOT" \
  --body "Weekly version bump. Bumps main to ${NEXT_VER}-SNAPSHOT so the release branch for v${RELEASE_VER} can be cut."
```

Merge through the standard PR process.

### 3) Create release branch

The release branch is created from the commit **before** the version bump.

```bash
cd ~/repos/viaduct
git checkout main && git pull origin main
git log --oneline -10
# Find the SHA BEFORE "chore: Bump version to ${NEXT_VER}-SNAPSHOT"
```

> **Warning:** Using the wrong SHA is the most common release mistake. The commit you pick must NOT contain the version bump.

```bash
git checkout -b release/v${RELEASE_VER} <SHA_BEFORE_BUMP>
```

> **Warning:** Branch name **must** contain the `v` prefix (e.g., `release/v0.29.0`).

**Set the release version:**

```bash
echo "${RELEASE_VER}" > VERSION
./gradlew syncDemoAppVersions
```

**Commit and push directly to the public repo:**

```bash
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}"
git push origin release/v${RELEASE_VER}
```

### 4) Validate build

```bash
gh workflow run ci-trigger.yml \
  --repo airbnb/viaduct \
  --ref release/v${RELEASE_VER}
```

Monitor:

```bash
gh run list --workflow=ci-trigger.yml --repo airbnb/viaduct --limit 5
```

This runs three sub-workflows:
1. **build-and-test** — compiles and runs all unit tests
2. **demoapps-ci-check** — tests all demo apps against locally-published artifacts (Java 17 + 21)
3. **bcv-api-check** — binary API compatibility check

Wait for all jobs to pass (15-30 minutes).

### 5) Generate changelog

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER}

uvx --with python-semantic-release python \
  .github/scripts/generate_changelog.py \
  origin/release/v${PREV_VER} HEAD \
  > /tmp/release-v${RELEASE_VER}-changelog.md
```

Edit the output: remove bookkeeping commits (version bumps), clarify cryptic messages, group related changes.

Share in the team Slack channel for review before the release meeting.

### 6) Confirm release

At the team meeting, present the changelog and get approval.

If last-minute changes need to be included:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER}
git cherry-pick <commit-sha>
git push origin release/v${RELEASE_VER}
```

If you cherry-picked, re-run Step 4 to validate.

### 7) Publish release

> **Warning:** This publishes to Maven Central and Gradle Plugin Portal. Once published, a version cannot be unpublished from Maven Central.

This single command publishes artifacts, pushes demo apps to standalone repos, verifies them, creates the release tag, and publishes the GitHub release:

```bash
gh workflow run release.yml \
  --repo airbnb/viaduct \
  -f release_version=${RELEASE_VER} \
  -F release_notes=/tmp/release-v${RELEASE_VER}-changelog.md
```

> **Note:** The `-F` flag (capital F) reads the file contents and uploads them as the `release_notes` input. The `-f` flag (lowercase) passes a literal string.

Monitor:

```bash
gh run list --workflow=release.yml --repo airbnb/viaduct --limit 3
```

The workflow runs these steps in sequence:
1. **Preflight** — verifies release branch exists and tag does not
2. **Publish** — validates versions, runs checks, publishes to Plugin Portal + Maven Central (via `publish-branch.yml`)
3. **Push demo apps** — updates standalone `viaduct-dev/*` repos (via `push-demoapps.yml`)
4. **Verify demo apps** — confirms standalone repos build against published artifacts
5. **Create release** — creates the `v${RELEASE_VER}` tag and publishes the GitHub release with your changelog

### 8) Verify

After the workflow completes:

```bash
open https://github.com/airbnb/viaduct/releases
# Confirm your release shows as "Latest"
```

Share the release link in Slack.

**Validate Star Wars demo app locally:**

```bash
# First time: clone the standalone repo
git clone git@github.com:viaduct-dev/starwars.git ~/repos/starwars

# Subsequent releases: pull the latest pushed by the workflow
cd ~/repos/starwars && git pull

# Run the tests — they build against the just-published Maven artifacts
./gradlew test
```

A passing build confirms the published artifacts are resolvable and the demo app compiles and tests against the new version.

## Gradle Tasks Reference

| Task | Purpose |
|------|---------|
| `syncDemoAppVersions` | Copies VERSION to all demo app `gradle.properties` files |
| `confirmDemoAppVersions` | Validates all demo app versions match VERSION (fails on mismatch) |
| `setReleaseCandidateVersion -PrcNumber=N` | Stamps `X.Y.Z-rc.N-SNAPSHOT` on release branches (must be on `release/vX.Y.Z`) |
| `printVersion` | Prints the current computed version |

## Troubleshooting

### `validate_release_state.py` not found

Your branch doesn't have the script. Pull the latest:

```bash
git pull origin main  # or git pull origin release/v${RELEASE_VER}
```

### `confirmDemoAppVersions` fails with mismatches

Run `./gradlew syncDemoAppVersions` to fix, then re-commit:

```bash
./gradlew syncDemoAppVersions
git add demoapps/*/gradle.properties
git commit -m "chore: Sync demoapp versions"
git push origin <your-branch>
```

### `setReleaseCandidateVersion` fails with branch name error

You must be on a branch named `release/vX.Y.Z`:

```bash
git branch --show-current  # Check your branch
git checkout release/v${RELEASE_VER}
```

### `release.yml` preflight fails — branch not found

The release branch must exist before triggering `release.yml`. Create it in Step 3.

### `release.yml` preflight fails — tag already exists

The version has already been released. If this was a mistake, contact the team before proceeding.

### Publication fails partway through

If `release.yml` fails after some artifacts are published (e.g., Plugin Portal succeeded but Maven Central failed), you can re-run the workflow. The Plugin Portal steps check for existing versions and skip them automatically. Maven Central and tagging are idempotent on retry.

### Demo app tests fail after push

Possible causes:
1. **Maven Central propagation delay** — artifacts not yet visible. Wait 10-15 minutes and the `verify-demoapps` step will retry.
2. **Stale Copybara content** — check the workflow output for warnings.

### Wrong SHA used for release branch

If the release branch includes the version bump commit:

```bash
git checkout main
git branch -D release/v${RELEASE_VER}
git push origin --delete release/v${RELEASE_VER}
git log --oneline -10
# Find the correct SHA, recreate the branch
git checkout -b release/v${RELEASE_VER} <CORRECT_SHA>
```

> Only delete a remote branch if no artifacts have been published from it.
