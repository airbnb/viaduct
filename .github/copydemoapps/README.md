# Demo App Publisher

This directory is a self-contained Gradle project for publishing Viaduct's demo
applications to their standalone GitHub repositories in the
[viaduct-dev](https://github.com/viaduct-dev) organization:

- `demoapps/cli-starter` → `viaduct-dev/cli-starter`
- `demoapps/jetty-starter` → `viaduct-dev/jetty-starter`
- `demoapps/ktor-starter` → `viaduct-dev/ktor-starter`
- `demoapps/micronaut-starter` → `viaduct-dev/micronaut-starter`
- `demoapps/starwars` → `viaduct-dev/starwars`

Publishing is step 10 of the release process described in `RELEASE-RUNBOOK.md`.

## Theory of Operation

[Copybara](https://github.com/google/copybara) runs in **SQUASH mode**. It does
not replay individual commits from the Viaduct monorepo. Instead, given a source
ref (e.g., `release/v0.7.0`), it:

1. Takes a snapshot of `demoapps/<app>/` at that ref
2. Strips the `demoapps/<app>/` path prefix so files land at the root of the
   destination repo
3. Squashes everything into a single commit and pushes it to `main` of the
   destination repo

The `--force` flag (always used here) tells Copybara to ignore any prior
migration state and do a clean squash from scratch. This is intentional — the
standalone repos are meant to be clean, self-contained starting points, not a
replay of the monorepo's commit history.

Copybara is downloaded on demand from GitHub releases (always the latest
release) and cached in `build/`. Run `./gradlew clean` to force a fresh
download. The Gradle wrapper pulls Java 21 via the toolchain resolver if not
already installed.

## Prerequisites

**SSH access to GitHub** (for local use):

```bash
ssh -T git@github.com
```

Expected: `Hi <username>! You've successfully authenticated...`

If this fails, see the setup instructions in `RELEASE-RUNBOOK.md`.

**For CI/GitHub Actions:** set the `VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN`
environment variable. The `copy` script will use HTTPS with that token.


## Scripts

### `copy` — high-level convenience script

Copies one demo app. Handles destination URL and committer identity automatically.

```
copy DEMOAPP [SOURCE_REF] [EXTRA_COPYBARA_ARGS...]
```

- `DEMOAPP` — one of `cli-starter`, `jetty-starter`, `ktor-starter`, `micronaut-starter`, `starwars`
- `SOURCE_REF` — git ref to copy from (default: current release branch)
- `--target-ref=BRANCH` — push and fetch to `BRANCH` instead of `main`; must precede any `EXTRA_COPYBARA_ARGS`
- `EXTRA_COPYBARA_ARGS` — any additional Copybara flags (see below)

### `run` — low-level wrapper

Invokes `./gradlew runCopybara` with the local `copy.bara.sky` config and repo
root already set up. Accepts any Copybara command and arguments:

```
run migrate WORKFLOW SOURCE_REF [OPTIONS]
```

Use `copy` for normal operations. `run` is there if you need to pass unusual
options not covered by `copy`.

## Production Run

This must be done on the release branch (e.g., `release/v0.7.0`). With no
explicit SOURCE_REF, `copy` infers it from the current branch and rejects
non-release branches with a hard error.

```bash
cd ~/repos/viaduct-public
git checkout release/v0.7.0

for app in cli-starter jetty-starter ktor-starter micronaut-starter starwars; do
    .github/copydemoapps/copy "$app"
done
```

If you need to run from a different branch, pass the ref explicitly:

```bash
.github/copydemoapps/copy starwars release/v0.7.0
```

After each copy, clone the destination repo to a temporary directory and verify tests pass:

```bash
git clone git@github.com:viaduct-dev/starwars.git /tmp/verify-starwars
cd /tmp/verify-starwars && ./gradlew clean test --scan
```

Verify in the build scan that the correct release version is in use.

## Diagnostic Runs

### Dry run — validates config without pushing

Shows what Copybara would do, but makes no changes to any repository.

```bash
.github/copydemoapps/copy starwars release/v0.7.0 --dry-run
```

This is useful for quickly checking that the config is syntactically valid and
that the expected files are being selected, but it does **not** test git
authentication or the actual push mechanism.

### Test branch run — full end-to-end without touching `main`

For a true end-to-end test (including auth and the actual push), create a
throwaway branch on the destination repo first, then run `copy` targeting that
branch via the `--git-destination-push` and `--git-destination-fetch` flags.

**Step 1:** Clone the test branch to a temporary directory

```bash
git clone git@github.com:viaduct-dev/starwars.git /tmp/verify-starwars
```

**Step 2:** Create a test branch on the destination

It's easiest to do this with your local copy:

```bash
(cd /tmp/verify-starwars && git checkout main && git pull && git checkout -b test/$(date +%Y%m%d) && git push -u origin HEAD)
```

Or you can use the GitHub CLI:

```bash
gh api repos/viaduct-dev/starwars/git/refs \
    -f ref="refs/heads/test/$(date +%Y%m%d)" \
    -f sha="$(gh api repos/viaduct-dev/starwars/git/ref/heads/main --jq '.object.sha')"
```

**Step 3:** Run the copy targeting that branch:

```bash
.github/copydemoapps/copy starwars HEAD --target-ref=test/$(date +%Y%m%d)
```

**Step 4:** Validate

```bash
(cd /tmp/verify-starwars && git pull && ./gradlew clean test --scan)
```

**Step 5:** Delete the test branch when done:

```bash
gh api -X DELETE repos/viaduct-dev/starwars/git/refs/heads/test/$(date +%Y%m%d)
```

This procedure exercises the full pipeline — Copybara download, git auth, config
resolution, file selection, and push — in a way that faithfully reproduces what a
production run does.

## Adding a New Demo App

1. Add the app name to `DEMO_APPS` in `copy.bara.sky`
2. Create the destination repo in the `viaduct-dev` org
3. Run a production copy on the first release that includes the new app
4. Update all appropriate documentation
