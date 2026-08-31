# Cutting a Viaduct Release

## Overview

The release process follows these steps:

1. **Bump version** on main to the next SNAPSHOT (e.g., `0.8.0-SNAPSHOT`)
2. **Create release branch** from the commit before the bump (e.g., `release/v0.7.0` with initial VERSION `0.7.0`)
3. **Validate** — run CI across all Java/OS combinations
4. **(Optional) Publish release candidate** — inspect the published-jar SBOMs, then publish `X.Y.Z-rc.N` artifacts, push RC demo apps, and verify them
5. **Generate changelog** and review with the team
6. **Confirm release** at the team meeting
7. **Publish final release** — inspect the published-jar SBOMs, then run one command that publishes `X.Y.Z`, pushes demo apps to `main`, verifies them, creates the tag, and publishes the GitHub release
8. **Verify** — pull and test all the published demo apps against the published artifacts

## Quick Command Reference

Set these shell variables before starting. `RELEASE_VER` is the base version you are preparing this week. `RC_VER` is optional and only used when you cut a release candidate; it is just the suffix portion (for example `rc.1`). `NEXT_VER` is the version that will replace `RELEASE_VER` on main.

```bash
export PREV_VER="0.28.0" \
export RELEASE_VER="0.29.0" \
export RC_VER="rc.1" \
export NEXT_VER="0.30.0" \
export GH_USER="your-github.com-username"
```

| Step                                                                         | Command |
|------------------------------------------------------------------------------|---------|
| [2. Version bump](#2-bump-version-on-main)                                   | `./gradlew setVersion -PsetVersion=${NEXT_VER}-SNAPSHOT`, PR |
| [3. Release branch](#3-create-release-branch)                                | `git checkout -b release/v${RELEASE_VER}`, `./gradlew setVersion -PsetVersion=${RELEASE_VER}`, push |
| [4. Validate build](#4-validate-build)                                       | `gh workflow run ci-trigger.yml --ref release/v${RELEASE_VER}` |
| [5. Publish RC](#5-publish-release-candidate-optional)                       | `./gradlew setVersion -PsetVersion=${RELEASE_VER}-${RC_VER}`, commit, push, `gh workflow run release.yml -f release_version=${RELEASE_VER} -f rc_ver=${RC_VER}` |
| [6. Changelog](#6-generate-changelog)                                        | `generate_changelog.py origin/release/v${PREV_VER} HEAD` |
| [7. SBOM inspection](#pre-publication-sbom-inspection) (before each publish) | `./gradlew -p publications cyclonedxBom --no-configuration-cache`, then inspect |
| [8. Publish final release](#8-publish-final-release)                         | `gh workflow run release.yml -f release_version=${RELEASE_VER} -f final=true -F release_notes=@changelog.md` |
| [9. Verify](#9-verify)                                                       | clone/pull + `./gradlew test` all published demo apps |

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
- **On release branches before final publication:** Either `X.Y.Z` or `X.Y.Z-rc.N`
- **For a final release:** Exactly `X.Y.Z`

Release candidate numbering is sequential and never reused:

- **First RC:** `X.Y.Z-rc.1`
- **Second RC:** `X.Y.Z-rc.2`
- **Final release:** `X.Y.Z`

Do not publish the same RC version twice. If an RC needs fixes, increment `N`.

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

**Set the new snapshot version:**

```bash
./gradlew setVersion -PsetVersion=${NEXT_VER}-SNAPSHOT
```

**Verify the changes:**

```bash
git diff .
# Should show VERSION + 6 demoapps/*/gradle.properties changed
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
./gradlew setVersion -PsetVersion=${RELEASE_VER}
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

1. **build-and-test** — compiles and runs all unit tests, plus the coverage reports and summary
2. **demoapps-ci-check** — tests all demo apps against locally-published artifacts (Java 17 + 21)
3. **bcv-api-check** — binary API compatibility check

Wait for all jobs to pass (15-30 minutes).

### Pre-publication SBOM inspection

> **Run this before every publish — both Step 5 (RC) and Step 8 (final).** A published fat JAR
> must not bundle test/build-only libraries — a stray `testFixtures(...)` dependency can pull
> junit, mockk and friends into a shipped artifact while every test still passes. Take a quick
> look at each jar's SBOM first; `test-fixtures` is the most prone, but skim them all.

**1. Generate the SBOMs** on the commit you are about to publish:

```bash
cd ~/repos/viaduct && git checkout release/v${RELEASE_VER}
./gradlew -p publications cyclonedxBom --no-configuration-cache
```

One SBOM is written per fat JAR at `publications/<module>/build/reports/sbom/cyclonedx.json`
(`api`, `runtime`, `buildtime`, `test-fixtures`, `javaapi-api`, `javaapi-runtime`,
`javaapi-buildtime`). Each should list a few dozen dependencies; if a file is nearly empty the
configuration cache blanked it — the `--no-configuration-cache` flag prevents that, so re-run.

**2. Open each SBOM and skim the dependencies.** Open them in an editor or pager
(`more publications/*/build/reports/sbom/cyclonedx.json`), or list just the coordinates:

```bash
for f in publications/*/build/reports/sbom/cyclonedx.json; do
  echo "== $f =="
  python3 -c "import json,sys;[print(f\"{c.get('group','')}:{c['name']}:{c.get('version','')}\") for c in json.load(open(sys.argv[1])).get('components',[])]" "$f"
done | less
```

Normal entries look like:

```
com.fasterxml.jackson.core:jackson-databind:2.17.3
com.google.guava:guava:33.3.1-jre
org.slf4j:slf4j-api:2.0.7
```

**3. What to look for.** A published runtime jar should contain **none** of these — nor anything
with `test`, `assert`, or `mock` in the name:

```
junit   mockk   mockito   byte-buddy   objenesis   javassist   assertj   strikt   hamcrest   truth
```

A line like `io.mockk:mockk:1.13.x` or `org.junit.jupiter:junit-jupiter:5.x` means a test/build
dependency leaked into a published artifact — **stop**, fix the offending module's
`build.gradle.kts` (look for a stray `testFixtures(...)` or a test-lib `api`/`implementation`),
and re-cut before publishing.

> **Expected — not a leak:** `kotlin`, `kotest` and `opentest4j` can appear in the SBOM. It
> lists the full dependency graph, but the fat-jar build strips those from the packaged jar (see
> the `exclude(...)` list in `publications/test-fixtures/build.gradle.kts`), so they are fine to
> see here. Focus on the libraries above.

### 5) Publish release candidate (optional)

Use this when you want a public release candidate before the final release.

**Set the RC version on the release branch:**

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER}
./gradlew setVersion -PsetVersion=${RELEASE_VER}-${RC_VER}
```

**Commit and push the RC version change:**

```bash
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}-${RC_VER}"
git push origin release/v${RELEASE_VER}
```

> **Before publishing:** complete the [Pre-publication SBOM inspection](#pre-publication-sbom-inspection). Do not trigger the workflow if a fat JAR ships test/build-only libraries.

**Publish the RC:**

```bash
gh workflow run release.yml \
  --repo airbnb/viaduct \
  -f release_version=${RELEASE_VER} \
  -f rc_ver=${RC_VER}
```

Monitor:

```bash
gh run list --workflow=release.yml --repo airbnb/viaduct --limit 3
```

The workflow runs these steps in sequence:

1. **Preflight** — validates the `release_version` / `rc_ver` / `final` inputs and verifies the `release/v${RELEASE_VER}` branch exists
2. **Publish** — validates versions, runs checks, and publishes `${RELEASE_VER}-${RC_VER}` to Plugin Portal + Maven Central
3. **Push demo apps** — updates standalone `viaduct-dev/*` repos on `rc/v${RELEASE_VER}-${RC_VER}`
4. **Verify demo apps** — confirms standalone repos build against the published RC artifacts

It does **not** create a Git tag, publish a GitHub release, or push demo apps to `main`.

If you need another RC after fixes, cherry-pick the fixes onto `release/v${RELEASE_VER}`, bump to the next RC number, push, and rerun the RC workflow:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER}
git cherry-pick <commit-sha>
./gradlew setVersion -PsetVersion=${RELEASE_VER}-rc.2
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}-rc.2"
git push origin release/v${RELEASE_VER}
```

### 6) Generate changelog

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

### 7) Confirm release

At the team meeting, present the changelog and get approval.

If last-minute changes need to be included:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER}
git cherry-pick <commit-sha>
git push origin release/v${RELEASE_VER}
```

If you cherry-picked, re-run Step 4 to validate. If you already published an RC and still want a fresh RC after those changes, re-run Step 5 with the next RC number.

### 8) Publish final release

If the release branch is currently at an RC version, reset it back to the exact final version first:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER}
./gradlew setVersion -PsetVersion=${RELEASE_VER}
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}"
git push origin release/v${RELEASE_VER}
```

> **Warning:** This publishes to Maven Central and Gradle Plugin Portal. Once published, a version cannot be unpublished from Maven Central.

> **Before publishing:** complete the [Pre-publication SBOM inspection](#pre-publication-sbom-inspection) on the final commit. Do not trigger the workflow if a fat JAR ships test/build-only libraries.

This single command publishes artifacts, pushes demo apps to standalone repos, verifies them, creates the release tag, and publishes the GitHub release:

```bash
gh workflow run release.yml \
  --repo airbnb/viaduct \
  -f release_version=${RELEASE_VER} \
  -f final=true \
  -F release_notes=@/tmp/release-v${RELEASE_VER}-changelog.md
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

### 9) Verify

After the workflow completes:

```bash
open https://github.com/airbnb/viaduct/releases
# Confirm your release shows as "Latest"
```

Share the release link in Slack.

**Validate the published demo apps locally:**

Clone (first time) / pull and test every published demo app against the just-published Maven
Central artifacts. `spring-starter` is intentionally omitted and not published to `viaduct-dev`.

```bash
DEMOAPPS=~/repos/viaduct-demoapps
mkdir -p "$DEMOAPPS"
for da in cli-starter jetty-starter ktor-starter micronaut-starter starwars; do
  if [ -d "$DEMOAPPS/$da" ]; then (cd "$DEMOAPPS/$da" && git pull --quiet)
  else git clone "git@github.com:viaduct-dev/$da.git" "$DEMOAPPS/$da"; fi
  echo "=== $da ===" && (cd "$DEMOAPPS/$da" && ./gradlew test) || echo "⚠️  $da FAILED"
done
```

`./gradlew test` resolves the published artifacts from Maven Central (the standalone repos default
to it — no `USE_MAVEN_LOCAL`). A clean run across all apps confirms the published artifacts are
resolvable and that each demo app compiles and tests against the new version. micronaut-starter's
`test` additionally runs `installDist` — it builds the application distribution and starts the
packaged JVM — so this also exercises the packaged-distribution path.

## Gradle Tasks Reference

| Task | Purpose |
|------|---------|
| `printVersion` | Prints the current version |
| `setVersion -PsetVersion=X.Y.Z` | Sets VERSION to a given value with validation, syncs demo apps |
| `syncDemoAppVersions` | Copies VERSION to demo app `gradle.properties` files |
| `confirmDemoAppVersions` | Validates demo app versions match VERSION (fails on mismatch) |
| `bumpSnapshotVersion` | Inserts/replaces `-rc.XXXX` in a SNAPSHOT version, syncs demo apps |
| `unbumpSnapshotVersion` | Removes the `-rc.XXXX` marker from a SNAPSHOT version, syncs demo apps |
| `writePublishedCoordinates` | Records every published coordinate to `<module>/build/reports/publication/coordinates.txt`; `release.yml` probes these for CDN visibility |

`bumpSnapshotVersion` / `unbumpSnapshotVersion` are only for ephemeral SNAPSHOT publication testing. Public release candidates use explicit versions like `X.Y.Z-rc.N`.

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

### `release.yml` preflight fails — branch not found

The release branch must exist before triggering `release.yml`. Create it in Step 3.

### `release.yml` preflight fails — tag already exists

The version has already been released. If this was a mistake, contact the team before proceeding.

### `release.yml` preflight fails — invalid RC/final inputs

Exactly one mode must be selected:

- **RC publication:** pass `release_version=X.Y.Z` and `rc_ver=rc.N`
- **Final release:** pass `release_version=X.Y.Z` and `final=true`

### Publication fails partway through

If `release.yml` fails after some artifacts are published (e.g., Plugin Portal succeeded but Maven Central failed), you can re-run the workflow. The Plugin Portal steps check for existing versions and skip them automatically. Maven Central and tagging are idempotent on retry.

### RC publication fails partway through

If `release.yml` fails during an RC publication after publishing `${RELEASE_VER}-${RC_VER}`, do not reuse that RC version. Fix the issue, increment to the next RC version, and rerun the workflow.

### `Derive the published coordinates` fails

This step runs in `publish` before anything is pushed, so Maven Central and the Plugin Portal are untouched and no cleanup is needed. Either a build configuration failure, or `if-no-files-found: error` on a derivation that produced nothing. Reproduce with `./gradlew writePublishedCoordinates` at the release SHA.

A release branch cut before this step existed does not carry the task. Cherry-pick it onto the branch before releasing from it.

### `wait-for-new-artifacts` fails

Artifacts are already on Maven Central, but no tag or GitHub release exists yet. See [Publication fails partway through](#publication-fails-partway-through) before re-running.

- **`not visible after 30m of polling`** — the error names each missing `.pom` URL and its last HTTP status. A `429` or `503` means retry. A persistent `404` means that coordinate is not on the CDN: either the derivation lists something the release does not publish, or the publication dropped it.
- **`no coordinates found` / `no '<repository>' coordinates found`** — the `published-coordinates` artifact arrived with nothing the probe could use. The derivation itself runs in `publish`, so this points at the artifact hand-off rather than at the Gradle task.
- **`coordinates carry version X, expected Y`** — the published `VERSION` disagrees with the version being probed. Reaching this means the `validate` job's version check was bypassed, since it catches the same mismatch before publication.

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
