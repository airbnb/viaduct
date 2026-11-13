# Cutting a Viaduct Release

## Overview

The release process follows these steps:

1. **Choose the cut commit** — a commit on main whose CI is green
2. **Create release branch** from that commit (e.g., `release/v4.0.0` with initial VERSION `4.0.0-rc.1-SNAPSHOT`, or `4.0.0` when no release candidate is planned)
3. **Bump version** on main to the next SNAPSHOT (e.g., `5.0.0-SNAPSHOT`)
4. **Validate** — run CI across all Java/OS combinations
5. **(Optional) Publish release candidate** — inspect the published jars, then publish `X.Y.Z-rc.N-SNAPSHOT` artifacts to the Central snapshot repository, push RC demo apps, and verify them
6. **Generate changelog** and review with the team
7. **Confirm release** at the team meeting
8. **Publish final release** — inspect the published jars, then run one command that publishes `X.Y.Z`, pushes demo apps to `main`, verifies them, creates the tag, and publishes the GitHub release
9. **Verify** — pull and test all the published demo apps against the published artifacts
10. **After the release** — update the version the docs pin, and add a release blog post

## Quick Command Reference

Set these shell variables before starting, and again in every new shell. `RELEASE_VER` is the version you are releasing. `RC_VER` is optional and only used when you cut a release candidate; it is just the suffix portion (for example `rc.1`). When you re-export in a later shell, use the RC number you are currently on, not `rc.1`. `NEXT_VER` is the version main moves to after the cut; Viaduct currently bumps the major version on every release. Step 1 adds `CUT_SHA` and Step 2 adds `BRANCH_VER`.

```bash
export PREV_VER="3.0.0"
export RELEASE_VER="4.0.0"
export RC_VER="rc.1"
export NEXT_VER="5.0.0"
export GH_USER="your-github.com-username"
```

Define this spot check in the same shell. It prints the HTTP status of the main published POMs on Maven Central. For a `-SNAPSHOT` version, such as an RC, it checks their metadata in the Central snapshot repository instead. `release.yml` itself probes every published coordinate.

```bash
check_poms() {
  v=$1
  base=https://repo.maven.apache.org/maven2
  case "$v" in *-SNAPSHOT) base=https://central.sonatype.com/repository/maven-snapshots ;; esac
  pom_file() { case "$v" in *-SNAPSHOT) echo maven-metadata.xml ;; *) echo "$1-$v.pom" ;; esac; }
  for a in api runtime buildtime test-fixtures javaapi-api javaapi-runtime javaapi-buildtime; do
    printf "%-20s -> %s\n" "$a" "$(curl -sL -o /dev/null -w '%{http_code}' \
      "$base/com/airbnb/viaduct/$a/$v/$(pom_file $a)")"
  done
  m=com.airbnb.viaduct.module-java-gradle-plugin.gradle.plugin
  printf "%-20s -> %s\n" "java plugin marker" "$(curl -sL -o /dev/null -w '%{http_code}' \
    "$base/com/airbnb/viaduct/module-java-gradle-plugin/$m/$v/$(pom_file $m)")"
}
```

| Step                                                               | Command |
|--------------------------------------------------------------------|---------|
| [1. Cut commit](#1-choose-the-cut-commit)                          | `gh run list --commit ${CUT_SHA}`, all green |
| [2. Release branch](#2-create-release-branch)                      | `git checkout -b release/v${RELEASE_VER} ${CUT_SHA}`, `./gradlew setVersion -PsetVersion=${BRANCH_VER}`, push |
| [3. Version bump](#3-bump-version-on-main)                         | `./gradlew setVersion -PsetVersion=${NEXT_VER}-SNAPSHOT`, PR |
| [4. Validate build](#4-validate-build)                             | `gh workflow run ci-trigger.yml --ref release/v${RELEASE_VER}`, after every push to the branch |
| [Inspection](#pre-publication-inspection) (before each publish)    | SBOMs, jar contents, `checkTestFixturesPublication` |
| [5. Publish RC](#5-publish-release-candidate-optional)             | `gh workflow run release.yml -f release_version=${RELEASE_VER} -f rc_ver=${RC_VER}` |
| [6. Changelog](#6-generate-changelog)                              | `generate_changelog.py origin/release/v${PREV_VER} origin/release/v${RELEASE_VER}` |
| [7. Confirm release](#7-confirm-release)                           | Team approval of the changelog |
| [8. Publish final release](#8-publish-final-release)               | `gh workflow run release.yml -f release_version=${RELEASE_VER} -f final=true -F release_notes=@/tmp/release-v${RELEASE_VER}-changelog.md` |
| [9. Verify](#9-verify)                                             | Maven Central POMs, clone/pull + `./gradlew test` all published demo apps |
| [10. After the release](#10-after-the-release)                     | Docs version pins, blog post, PR |

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

- **On main:** Always `X.Y.Z-SNAPSHOT` (e.g., `5.0.0-SNAPSHOT`)
- **On release branches before final publication:** Either `X.Y.Z` or `X.Y.Z-rc.N-SNAPSHOT`
- **For a final release:** Exactly `X.Y.Z`

Release candidate numbering is sequential and never reused:

- **First RC:** `X.Y.Z-rc.1-SNAPSHOT`
- **Second RC:** `X.Y.Z-rc.2-SNAPSHOT`
- **Final release:** `X.Y.Z`

Release candidates publish to the [Central snapshot repository](https://central.sonatype.org/publish/publish-portal-snapshots/), so they don't count against the Maven Central publishing limits. The repository deletes them after 90 days. Do not publish the same RC version twice: `release.yml` refuses to publish an RC version once any of its modules has finished uploading. If a published RC needs fixes, increment `N`. An RC that is not published yet can take fixes under the same number; see [Fix the branch before publishing](#fix-the-branch-before-publishing).

Demo apps have `gradle.properties` files with a `viaductVersion` property that **must match** the root VERSION. `setVersion` updates all 7 of them (`cli-starter`, `jetty-starter`, `ktor-starter`, `micronaut-starter`, `spring-starter`, `starwars`, `starwars-java`). Use Gradle tasks to keep them in sync:

```bash
./gradlew syncDemoAppVersions     # Copy VERSION to all demo apps
./gradlew confirmDemoAppVersions  # Validate all demo apps match VERSION (CI runs this automatically)
```

## Detailed Release Process

During the team meeting, a release manager is selected for the week.

> **Before every push to `release/v${RELEASE_VER}`:** confirm no release is running. The `release.yml` jobs that build, publish and push demo apps check out the branch by name, so a push during a run can mix commits. This must print nothing (a run can also be `queued` or `waiting`, not only `in_progress`):
>
> ```bash
> gh run list --repo airbnb/viaduct --workflow=release.yml --limit 20 \
>   --json status,databaseId --jq '.[] | select(.status != "completed") | .databaseId'
> ```
>
> **After every push:** re-run [Step 4](#4-validate-build). A push to a release branch does not start CI on its own.

### 1) Choose the cut commit

Pick the main commit to release from, and record its full SHA:

```bash
export CUT_SHA="<full-sha>"
```

Every workflow that ran on it must be green:

```bash
gh run list --repo airbnb/viaduct --commit ${CUT_SHA} \
  --json name,conclusion --jq '.[] | select(.conclusion != "skipped") | "\(.conclusion)\t\(.name)"'
```

Expect `success` for `CI Check`, and for `Nightly Build` if one has run on that commit. Then confirm nothing that must ship landed after it, and that the release branch does not exist yet:

```bash
gh api "repos/airbnb/viaduct/compare/${CUT_SHA}...main" --jq '.commits[] | .commit.message | split("\n")[0]'
gh api -i repos/airbnb/viaduct/branches/release/v${RELEASE_VER} 2>/dev/null | head -1   # must be: HTTP/2.0 404 Not Found
```

Anything other than `404` means the branch exists or the check failed: stop.

### 2) Create release branch

Cut the branch from `CUT_SHA`. Because the branch starts from a recorded SHA, it does not matter whether the Step 3 bump has merged yet.

```bash
cd ~/repos/viaduct
git checkout main && git fetch origin --prune --tags && git pull origin main
: "${CUT_SHA:?set CUT_SHA in Step 1}"
git log --oneline -1 ${CUT_SHA}
git checkout -b release/v${RELEASE_VER} ${CUT_SHA}
```

> **Warning:** Branch name **must** contain the `v` prefix (e.g., `release/v4.0.0`). `validate_release_state.py` accepts only `release/vX.Y.Z`.

**Set the release version.** If you plan a release candidate, set the RC version now; this saves a CI run in Step 5:

```bash
export BRANCH_VER="${RELEASE_VER}-${RC_VER}-SNAPSHOT"   # or "${RELEASE_VER}" when no RC is planned
./gradlew setVersion -PsetVersion=${BRANCH_VER}
```

**Verify before committing:**

```bash
cat VERSION                                                   # exactly ${BRANCH_VER}
git diff --stat                                               # VERSION + 7 demoapps/*/gradle.properties
grep -h viaductVersion demoapps/*/gradle.properties | sort -u # one line: viaductVersion=${BRANCH_VER}
./gradlew confirmDemoAppVersions                              # must pass
```

**Commit and push directly to the public repo:**

```bash
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${BRANCH_VER}"
git log --oneline ${CUT_SHA}..HEAD                            # exactly 1 commit
git push origin release/v${RELEASE_VER}
```

### 3) Bump version on main

Move main to the next SNAPSHOT. This can run in parallel with Steps 4–7; nothing in the release reads it.

**Sync your fork and create a branch:**

```bash
cd ~/repos/viaduct
git checkout main
git pull origin main
git push fork main
git checkout -b candidate/v${NEXT_VER}
```

The bump branch name is free-form; the tooling only parses `release/vX.Y.Z`.

**Set the new snapshot version:**

```bash
./gradlew setVersion -PsetVersion=${NEXT_VER}-SNAPSHOT
```

**Verify the changes:**

```bash
cat VERSION          # exactly ${NEXT_VER}-SNAPSHOT
git diff --stat      # VERSION + 7 demoapps/*/gradle.properties
```

**Commit, push to your fork, and create PR:**

Before opening the PR, confirm nobody has opened one already. This must print nothing:

```bash
gh pr list --repo airbnb/viaduct --state all --search "Bump version to ${NEXT_VER}-SNAPSHOT in:title"
```

```bash
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Bump version to ${NEXT_VER}-SNAPSHOT"
git push fork candidate/v${NEXT_VER}

gh pr create \
  --repo airbnb/viaduct \
  --base main \
  --head ${GH_USER}:candidate/v${NEXT_VER} \
  --title "chore: Bump version to ${NEXT_VER}-SNAPSHOT" \
  --body "Bumps main to ${NEXT_VER}-SNAPSHOT now that release/v${RELEASE_VER} is cut."
```

Merge through the standard PR process.

### 4) Validate build

A push to a release branch does **not** start CI: `ci-trigger.yml` runs on pushes to main and on PRs. Dispatch it after **every** push to `release/v${RELEASE_VER}`:

```bash
gh workflow run ci-trigger.yml \
  --repo airbnb/viaduct \
  --ref release/v${RELEASE_VER}
```

Monitor:

```bash
sleep 10
HEAD_SHA=$(gh api repos/airbnb/viaduct/branches/release/v${RELEASE_VER} --jq .commit.sha)
RUN_ID=$(gh run list --workflow=ci-trigger.yml --repo airbnb/viaduct --branch release/v${RELEASE_VER} \
  --limit 5 --json databaseId,headSha --jq "map(select(.headSha == \"${HEAD_SHA}\"))[0].databaseId")
echo "${RUN_ID}"
gh run watch ${RUN_ID} --repo airbnb/viaduct --exit-status
```

If `RUN_ID` is empty, the run for the branch head has not registered yet: wait a few seconds and repeat the `RUN_ID` line. Selecting by head SHA keeps you from watching an earlier run.

This runs three sub-workflows:

1. **build-and-test** — compiles and runs all unit tests, plus the coverage reports and summary
2. **demoapps-ci-check** — tests all demo apps against locally-published artifacts (Java 17, 21 and 25)
3. **bcv-api-check** — binary API compatibility check

Wait for all jobs to pass. Allow 1–2 hours. `release.yml` re-runs `./gradlew check` before publishing, on Linux only. If a job fails because of the code, fix it on main and bring the fix over with [Fix the branch before publishing](#fix-the-branch-before-publishing).

If `bcv-api-check` fails, a dump was missed on main. Fix it on the release branch rather than skipping it:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER} && git pull origin release/v${RELEASE_VER}
./gradlew runApiDump
git add -A -- '*.api'
git commit -m "chore: Regenerate API dumps for ${RELEASE_VER}"
git push origin release/v${RELEASE_VER}
```

Open a PR with the same dump change against main too, so a later [re-cut](#another-rc) keeps it. Then re-run this step.

### Pre-publication inspection

> **Run this before every publish — both Step 5 (RC) and Step 8 (final).** A published fat JAR must not bundle test/build-only libraries — a stray `testFixtures(...)` dependency can pull junit, mockk and friends into a shipped artifact while every test still passes. `test-fixtures` is the most prone, but check them all.

**1. Generate fresh SBOMs** on the commit you are about to publish:

```bash
cd ~/repos/viaduct && git checkout release/v${RELEASE_VER} && git pull origin release/v${RELEASE_VER}
./gradlew -p publications cyclonedxBom --rerun --no-configuration-cache --no-build-cache
```

The task logs many `Unable to resolve POM for com.airbnb.viaduct...` lines; they do not fail it. One SBOM is written per fat JAR at `publications/<module>/build/reports/sbom/cyclonedx.json` (`api`, `runtime`, `buildtime`, `test-fixtures`, `javaapi-api`, `javaapi-runtime`, `javaapi-buildtime`). Each should list a few dozen dependencies. A nearly empty file means the configuration cache blanked it; re-run the command, keeping `--no-configuration-cache`.

**2. List the coordinates and look for test libraries:**

```bash
for f in publications/*/build/reports/sbom/cyclonedx.json; do
  echo "== $f =="
  python3 -c "import json,sys;[print(f\"{c.get('group','')}:{c['name']}:{c.get('version','')}\") for c in json.load(open(sys.argv[1])).get('components',[])]" "$f"
done > /tmp/sbom-${RELEASE_VER}.txt
grep -c "^== " /tmp/sbom-${RELEASE_VER}.txt     # 7
grep -iE "junit|mockk|mockito|byte-buddy|objenesis|assertj|strikt|hamcrest|truth" /tmp/sbom-${RELEASE_VER}.txt
```

The SBOM lists the full dependency graph, including libraries the fat-jar build strips, so a hit here is a lead rather than a verdict. For every library the last command prints, and for any entry with `test`, `assert`, or `mock` in the name, check whether the jar from step 3 contains its package. If it does, a test/build dependency leaked into a published artifact — **stop**, fix the offending module's `build.gradle.kts` on main (look for a stray `testFixtures(...)` or a test-lib `api`/`implementation`), and bring the fix over with [Fix the branch before publishing](#fix-the-branch-before-publishing) before publishing.

> **Expected — not a leak:** `kotlin`, `kotest`, `opentest4j` and JUnit can appear in the `test-fixtures` SBOM through module test fixtures; the `exclude(...)` list in `publications/test-fixtures/build.gradle.kts` keeps them out of the jar. `javassist` is bundled on purpose in `buildtime`, which `javaapi-buildtime` depends on; both use it for code generation.

**3. Check the jar contents.** The jars are the ground truth:

```bash
./gradlew -p publications shadowJar --rerun --no-configuration-cache -q
for j in publications/*/build/libs/*-$(cat VERSION).jar; do
  printf "%-45s junit=%s mockk=%s mockito=%s assertj=%s\n" "$(basename $j)" \
    "$(unzip -l $j | grep -c 'org/junit')" "$(unzip -l $j | grep -c 'io/mockk')" \
    "$(unzip -l $j | grep -c 'org/mockito')" "$(unzip -l $j | grep -c 'org/assertj')"
done
```

Every count must be `0`. `buildtime` currently also ships `io/kotest` classes; that is a known issue, not a new leak.

> **Gradle trap:** after an `exclude(...)` change, `shadowJar` can report `UP-TO-DATE` and keep the old jar contents, with or without the build cache. Always pass `--rerun` when you inspect jar contents.

**4. Check the `test-fixtures` jar,** right after step 3 so it reads the freshly built jar. This task fails if any entry contains `org/junit/`, or if the jar bundles any class that `engine-api`'s test fixtures put under `viaduct/engine/runtime/execution/` (the engine contract suites). It also fails if that package is empty there:

```bash
./gradlew -p publications :test-fixtures:checkTestFixturesPublication --no-build-cache
```

Then check that no jar ships a class, resource or `META-INF/maven` descriptor that one of its lower jars ships. `runtime`, `test-fixtures`, `javaapi-api`, `javaapi-runtime` and `javaapi-buildtime` each leave out the classes of the jars they depend on in their POMs:

```bash
./gradlew -p publications checkFatJarLayers --no-build-cache
```

**5. (Optional) Scan for CVEs.** `security-scan.yml` is advisory and never fails the release. To get its report for the branch, dispatch it and read the run's summary:

```bash
gh workflow run security-scan.yml --repo airbnb/viaduct --ref release/v${RELEASE_VER}
```

### Fix the branch before publishing

When Step 4 or the inspection fails, the version on the branch head can keep its number as long as it is not published yet. This holds for an RC and for the final version. Check:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER} && git pull origin release/v${RELEASE_VER}
check_poms "$(cat VERSION)"                   # every line must be 404
```

A `200` means that version is published: stop, and follow [Another RC](#another-rc) for an RC, or fix forward in the next release for the final. Otherwise land the fix on main first, then cherry-pick it without changing `VERSION`:

```bash
git cherry-pick -x <full-sha-from-main>
cat VERSION                                   # unchanged
git push origin release/v${RELEASE_VER}
```

Then re-run [Step 4](#4-validate-build) and the inspection on the new head, and continue with the publish step you were on.

### 5) Publish release candidate (optional)

Use this when you want a public release candidate before the final release.

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER} && git pull origin release/v${RELEASE_VER}
cat VERSION                                                   # ${RELEASE_VER}-${RC_VER}-SNAPSHOT
```

If Step 2 set `${RELEASE_VER}` instead, set the RC version, commit and push, then re-run [Step 4](#4-validate-build):

```bash
./gradlew setVersion -PsetVersion=${RELEASE_VER}-${RC_VER}-SNAPSHOT
./gradlew confirmDemoAppVersions
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}-${RC_VER}-SNAPSHOT"
git push origin release/v${RELEASE_VER}
```

> **Before publishing:** Step 4 must be green on the branch head, and the [Pre-publication inspection](#pre-publication-inspection) must pass on it. Do not trigger the workflow if a fat JAR ships test/build-only libraries.

**Publish the RC.** An RC version is never reused, so first confirm this one is not published yet:

```bash
check_poms ${RELEASE_VER}-${RC_VER}-SNAPSHOT            # every line must be 404
gh repo view --json nameWithOwner --jq .nameWithOwner   # airbnb/viaduct
git status --porcelain                                  # empty
gh workflow run release.yml \
  --repo airbnb/viaduct \
  -f release_version=${RELEASE_VER} \
  -f rc_ver=${RC_VER}
```

Do **not** pass `final=true`; preflight rejects `final=true` together with `rc_ver`.

Monitor:

```bash
sleep 10
gh run list --workflow=release.yml --repo airbnb/viaduct --limit 3
```

A run takes about 1h10–1h30.

The workflow runs these steps in sequence:

1. **Preflight** — validates the `release_version` / `rc_ver` / `final` inputs and verifies the `release/v${RELEASE_VER}` branch exists
2. **Publish** — validates versions, runs checks, refuses an RC version that is already published, and publishes `${RELEASE_VER}-${RC_VER}-SNAPSHOT` to the Central snapshot repository. Nothing goes to Maven Central or the Gradle Plugin Portal
3. **Push demo apps** — updates the 5 standalone `viaduct-dev/*` repos on `rc/v${RELEASE_VER}-${RC_VER}`
4. **Wait for new artifacts** — polls the snapshot repository until every published coordinate is visible, then checks that every published file has a valid signature from the release key in `.github/release-signing-key.asc`
5. **Verify demo apps** — confirms standalone repos build against the published RC artifacts

It does **not** create a Git tag, publish a GitHub release, or push demo apps to `main`.

**Spot-check the RC** in the snapshot repository with the `check_poms` helper from the [Quick Command Reference](#quick-command-reference):

```bash
check_poms ${RELEASE_VER}-${RC_VER}-SNAPSHOT
```

Every line must print `200`. Share the RC version, and the `rc/v${RELEASE_VER}-${RC_VER}` demo-app branches, with whoever is testing it. The demo apps resolve the RC with `USE_VIADUCT_SNAPSHOT_REPO=true ./gradlew test`. The RC's plugins are not on the Gradle Plugin Portal, so other projects add the snapshot repository to `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
}
```

#### Another RC

Each new RC uses the next unused number. Set it first, so every command below and in Step 5 uses it, and record the branch's current head, which is the previous RC:

```bash
export RC_VER="rc.2"   # the next unused rc.N
gh api repos/airbnb/viaduct/branches/release/v${RELEASE_VER} --jq .commit.sha   # OLD_HEAD: write it down
```

There are two ways to bring changes onto the release branch.

**Cherry-pick** a few fixes from main:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER} && git pull origin release/v${RELEASE_VER}
git cherry-pick -x <full-sha-from-main>
./gradlew setVersion -PsetVersion=${RELEASE_VER}-${RC_VER}-SNAPSHOT
./gradlew confirmDemoAppVersions
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}-${RC_VER}-SNAPSHOT"
git push origin release/v${RELEASE_VER}
```

**Re-cut** the branch from a newer main commit when the RC needs much of main, such as a series of breaking changes. Re-cut only while the final `${RELEASE_VER}` is not on Maven Central; after that, fix forward. Check:

```bash
curl -sL -o /dev/null -w '%{http_code}\n' \
  "https://repo.maven.apache.org/maven2/com/airbnb/viaduct/api/${RELEASE_VER}/api-${RELEASE_VER}.pom"   # must be 404
```

Choose the new cut commit with Step 1's CI and compare checks; skip its branch-exists check, since the branch exists by design here.

A re-cut keeps only the new cut commit plus one version commit, so first confirm that nothing exists only on the branch:

```bash
cd ~/repos/viaduct && git fetch origin --prune --tags
for c in $(git rev-list <NEW_CUT_SHA>..origin/release/v${RELEASE_VER}); do
  s=$(git log -1 --format=%s "$c")
  o=$(git log -1 --format=%b "$c" | sed -n 's/^(cherry picked from commit \([0-9a-f]*\))$/\1/p')
  if [[ "$s" == "chore: Set version to "* ]]; then echo "ok    version commit: $s"
  elif [ -n "$o" ] && git merge-base --is-ancestor "$o" <NEW_CUT_SHA>; then echo "ok    already in the new cut: $s"
  elif git cherry <NEW_CUT_SHA> "$c" "$c^" | grep -q '^-'; then echo "ok    same change already in the new cut: $s"
  else echo "KEEP  $(git log -1 --format=%h "$c") $s"; fi
done
```

Every `KEEP` line is a change the new cut does not contain, such as an API-dump fix that has not reached main. Land it on main, choose a newer cut that contains it, and run the loop again until it prints no `KEEP` line. If a change cannot wait for main, cherry-pick each `KEEP` commit with `-x` right after `git checkout -B` and before `setVersion`; the two checks below then expect `1 + N` commits and `ahead=1+N` for `N` cherry-picks. Then re-cut:

```bash
git checkout -B release/v${RELEASE_VER} <NEW_CUT_SHA>
./gradlew setVersion -PsetVersion=${RELEASE_VER}-${RC_VER}-SNAPSHOT
./gradlew confirmDemoAppVersions
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}-${RC_VER}-SNAPSHOT"
git log --oneline <NEW_CUT_SHA>..HEAD                                            # exactly 1 commit
git push --force-with-lease=release/v${RELEASE_VER}:<OLD_HEAD> origin release/v${RELEASE_VER}
gh api repos/airbnb/viaduct/compare/<NEW_CUT_SHA>...release/v${RELEASE_VER} \
  --jq '"ahead=\(.ahead_by) behind=\(.behind_by)"'                              # ahead=1 behind=0
```

Write `<OLD_HEAD>` and `<NEW_CUT_SHA>` as literal SHAs. If the push is rejected, the branch moved after you read it: stop and investigate rather than retrying with `--force`. Earlier RCs stay in the snapshot repository for 90 days, and on their `rc/v…` demo-app branches.

After either path, re-run Step 4 and the inspection, then continue at Step 5's **Publish the RC**. Update the approved changelog with only what changed since the previous RC, rather than regenerating it and losing its edits: `generate_changelog.py <OLD_HEAD> origin/release/v${RELEASE_VER}`.

### 6) Generate changelog

```bash
cd ~/repos/viaduct
git fetch origin --prune

uvx --with python-semantic-release python \
  .github/scripts/generate_changelog.py \
  origin/release/v${PREV_VER} origin/release/v${RELEASE_VER} \
  > /tmp/release-v${RELEASE_VER}-changelog.md
```

Edit the output: remove bookkeeping commits (`chore: Set version to …`, `chore: Bump version to …`, `chore: Regenerate API dumps …`), clarify cryptic messages, group related changes. Put highlights and an "Upgrading from ${PREV_VER}" section first. Keep a copy of the approved notes outside `/tmp`, which can be cleared on reboot.

Check the breaking changes before sharing:

- **The `!` markers undercount.** Use the API dumps as the inventory, and give every real break a migration line, including commits that are not marked `!`:
  ```bash
  git diff origin/release/v${PREV_VER} origin/release/v${RELEASE_VER} -- '*.api'
  ```
- **Footers can be cut short.** A breaking entry carries its `BREAKING CHANGE:` footer, but the footer stops at its first blank line or bullet list, so multi-paragraph migration text is truncated. A `!` commit without a footer shows only its subject.
- **Trailers can leak in.** A trailer with no blank line before it, such as `Co-authored-by: Name <email>`, is merged into the footer. Search the notes for `Co-authored-by` and for `<` to find and remove email addresses.
- **Links can appear while you edit.** The published 3.0.0 notes link `AGENTS.md` to `http://AGENTS.md`, a link its commit message does not contain. Every hit of `grep -n "http://" /tmp/release-v${RELEASE_VER}-changelog.md` must be a link you wrote on purpose.
- **Every `@` outside backticks must be an author.** The script credits each commit's GitHub login, but it copies descriptions as written, so a directive such as `@parent` mentions the GitHub user of that name. Put directives and annotations in backticks. Every line this prints needs a fix:
  ```bash
  sed -E 's/`[^`]*`//g; s/ by (@?[^@,—]+)(, @?[^@,—]+)*( —|$)/\3/' /tmp/release-v${RELEASE_VER}-changelog.md | grep -n '@'
  ```

Share in the team Slack channel for review before the release meeting.

### 7) Confirm release

At the team meeting, present the changelog and get approval.

If last-minute changes need to be included:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER} && git pull origin release/v${RELEASE_VER}
git cherry-pick -x <commit-sha>
git push origin release/v${RELEASE_VER}
```

If you cherry-picked, re-run Step 4 to validate. If you already published an RC and still want a fresh RC after those changes, follow [Another RC](#another-rc).

### 8) Publish final release

If the release branch is currently at an RC version, reset it back to the exact final version first:

```bash
cd ~/repos/viaduct
git checkout release/v${RELEASE_VER} && git pull origin release/v${RELEASE_VER}
./gradlew setVersion -PsetVersion=${RELEASE_VER}
./gradlew confirmDemoAppVersions
git add VERSION demoapps/*/gradle.properties
git commit -m "chore: Set version to ${RELEASE_VER}"
git push origin release/v${RELEASE_VER}
```

> **Warning:** This publishes to Maven Central and Gradle Plugin Portal. Once published, a version cannot be unpublished from Maven Central.

> **Before publishing:** Step 4 must be green on the final commit, and the [Pre-publication inspection](#pre-publication-inspection) must pass on it. Do not trigger the workflow if a fat JAR ships test/build-only libraries.

**Pre-flight:**

```bash
gh repo view --json nameWithOwner --jq .nameWithOwner   # airbnb/viaduct
git branch --show-current                               # release/v${RELEASE_VER}
git status --porcelain                                  # empty
cat VERSION                                             # exactly ${RELEASE_VER}: no -SNAPSHOT, no -rc.N
./gradlew confirmDemoAppVersions                        # passes
gh api -i repos/airbnb/viaduct/git/ref/tags/v${RELEASE_VER} 2>/dev/null | head -1   # must be: HTTP/2.0 404 Not Found
head -3 /tmp/release-v${RELEASE_VER}-changelog.md       # the approved notes
grep -n "http://" /tmp/release-v${RELEASE_VER}-changelog.md   # only links you wrote on purpose
```

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
sleep 10
gh run list --workflow=release.yml --repo airbnb/viaduct --limit 3
```

A run takes about 1h10–1h30.

The run lists main's SHA as its head because it is dispatched from main; `publish-branch.yml` checks out `release/v${RELEASE_VER}` to build and publish.

The workflow runs these steps in sequence:

1. **Preflight** — verifies release branch exists and tag does not
2. **Publish** — validates versions, runs checks, publishes to Plugin Portal + Maven Central (via `publish-branch.yml`)
3. **Push demo apps** — updates the 5 standalone `viaduct-dev/*` repos on `main` (via `push-demoapps.yml`)
4. **Wait for new artifacts** — polls Maven Central and the Gradle Plugin Portal until every published coordinate is visible
5. **Verify demo apps** — confirms standalone repos build against published artifacts
6. **Create release** — creates the `v${RELEASE_VER}` tag and publishes the GitHub release with your changelog

**Never change `VERSION` and re-cut once the final version is public.** Fix forward in the next release.

### 9) Verify

After the workflow completes:

```bash
gh api repos/airbnb/viaduct/releases/latest --jq .tag_name   # v${RELEASE_VER}
check_poms ${RELEASE_VER}                                    # Quick Command Reference helper; every line 200
```

Share the release link in Slack.

**Validate the published demo apps locally:**

Clone (first time) / pull and test every published demo app against the just-published Maven Central artifacts. `spring-starter` and `starwars-java` are not published to `viaduct-dev`.

```bash
DEMOAPPS=~/repos/viaduct-demoapps
mkdir -p "$DEMOAPPS"
for da in cli-starter jetty-starter ktor-starter micronaut-starter starwars; do
  if [ -d "$DEMOAPPS/$da" ]; then (cd "$DEMOAPPS/$da" && git checkout main --quiet && git pull --quiet)
  else git clone "git@github.com:viaduct-dev/$da.git" "$DEMOAPPS/$da"; fi
  echo "=== $da ===" && (cd "$DEMOAPPS/$da" && grep -h viaductVersion gradle.properties && ./gradlew test) || echo "⚠️  $da FAILED"
done
```

Each app must report `viaductVersion=${RELEASE_VER}`. `./gradlew test` resolves the published artifacts from Maven Central (the standalone repos default to it — no `USE_MAVEN_LOCAL`). A clean run across all apps confirms the published artifacts are resolvable and that each demo app compiles and tests against the new version. micronaut-starter's `test` additionally runs `installDist` — it builds the application distribution and starts the packaged JVM — so this also exercises the packaged-distribution path.

### 10) After the release

**Update the version the docs pin.** The getting-started pages pin Viaduct plugin and artifact versions in their Gradle snippets. Find them on a branch from main:

```bash
cd ~/repos/viaduct
git checkout main && git pull origin main
git checkout -b docs-v${RELEASE_VER}
git grep -n -F "${PREV_VER}" -- docs/ ':!docs/docs/blog'
```

Change each hit that pins a Viaduct version to `${RELEASE_VER}`.

**Add a release blog post** at `docs/docs/blog/posts/<YYYY-MM-DD>-release-X-Y-Z/index.md`, following the previous release's post.

Push the branch to your fork and open a PR against main as in [Step 3](#3-bump-version-on-main).

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
| `-p publications :test-fixtures:checkTestFixturesPublication` | Fails if the `test-fixtures` jar bundles JUnit or `engine-api`'s test-fixture classes under `viaduct/engine/runtime/execution/` |
| `-p publications checkFatJarLayers` | Fails if a fat jar ships a class, resource or `META-INF/maven` descriptor that one of its lower jars (its POM dependencies) ships; other top-level `META-INF` files are exempt |

`bumpSnapshotVersion` / `unbumpSnapshotVersion` are only for ephemeral SNAPSHOT publication testing. Public release candidates use explicit versions like `X.Y.Z-rc.N-SNAPSHOT`.

## Troubleshooting

### `confirmDemoAppVersions` fails with mismatches

Run `./gradlew syncDemoAppVersions` to fix, then re-commit:

```bash
./gradlew syncDemoAppVersions
git add demoapps/*/gradle.properties
git commit -m "chore: Sync demoapp versions"
git push origin <your-branch>
```

### A push to the release branch did not start CI

Expected: `ci-trigger.yml` does not run on release-branch pushes. Dispatch it as in [Step 4](#4-validate-build).

### `release.yml` preflight fails — branch not found

The release branch must exist before triggering `release.yml`. Create it in Step 2.

### `release.yml` preflight fails — tag already exists

The version has already been released. If this was a mistake, contact the team before proceeding.

### `release.yml` preflight fails — invalid RC/final inputs

Exactly one mode must be selected:

- **RC publication:** pass `release_version=X.Y.Z` and `rc_ver=rc.N`
- **Final release:** pass `release_version=X.Y.Z` and `final=true`

### `publish` fails in `check` on a flaky test

Nothing is published before `check` passes, so re-dispatching the same version is safe. Confirm the failure is unrelated to the release branch first.

### Publication fails partway through

If `release.yml` fails in a job after `publish` succeeded (`push-demoapps`, `wait-for-new-artifacts` or `verify-demoapps`), re-run only the failed jobs rather than dispatching again. This applies to an RC as well: the RC version is already published, and re-running those jobs does not publish it again.

```bash
gh run rerun <RUN_ID> --repo airbnb/viaduct --failed
```

If `publish` itself failed after part of the upload (e.g., Plugin Portal succeeded but Maven Central failed), investigate before re-running: the publish tasks do not skip versions that are already uploaded, so a re-run can fail on the part that succeeded. For an RC, the re-run stops at `Refuse to overwrite a published release candidate`, so move to the next RC number; see below.

### `create-release` fails after creating the tag

`create-release` creates the `v${RELEASE_VER}` tag first and then the GitHub release, so re-running the job fails on the tag that already exists. Check that the tag points at the release branch head, then publish the release directly:

```bash
gh api repos/airbnb/viaduct/git/ref/tags/v${RELEASE_VER} --jq .object.sha
gh api repos/airbnb/viaduct/branches/release/v${RELEASE_VER} --jq .commit.sha   # must match the line above
gh release create v${RELEASE_VER} --repo airbnb/viaduct --verify-tag \
  --title "Release ${RELEASE_VER}" --notes-file /tmp/release-v${RELEASE_VER}-changelog.md --latest
```

If the two SHAs differ, stop and ask the team.

### RC publication fails partway through

If `release.yml` fails during an RC publication after publishing `${RELEASE_VER}-${RC_VER}-SNAPSHOT`, do not reuse that RC version. Fix the issue, increment to the next RC version, and rerun the workflow.

### `Refuse to overwrite a published release candidate` fails

The error names each URL that did not return `404`. A `200` means that RC version is already in the snapshot repository, at least partly: move to the next RC number. Any other status means the repository could not confirm the version is absent; re-run the failed jobs.

### `Derive the published coordinates` fails

This step runs in `publish` before anything is pushed, so Maven Central and the Plugin Portal are untouched and no cleanup is needed. Either a build configuration failure, or `if-no-files-found: error` on a derivation that produced nothing. Reproduce with `./gradlew writePublishedCoordinates` at the release SHA.

A release branch cut before this step existed does not carry the task. Cherry-pick it onto the branch before releasing from it.

### `wait-for-new-artifacts` fails

Artifacts are already on Maven Central, but no tag or GitHub release exists yet. See [Publication fails partway through](#publication-fails-partway-through) before re-running.

- **`not visible after 30m of polling`** — the error names each missing `.pom` URL and its last HTTP status. A `429` or `503` means retry. A persistent `404` means that coordinate is not on the CDN: either the derivation lists something the release does not publish, or the publication dropped it.
- **`no coordinates found` / `no '<repository>' coordinates found`** — the `published-coordinates` artifact arrived with nothing the probe could use. The derivation itself runs in `publish`, so this points at the artifact hand-off rather than at the Gradle task.
- **A signature error** (RC mode), such as `missing signature: <url>.asc` or `BADSIG: <url>` — the RC is published but a file is unsigned, altered, or signed by another key. `NO_PUBKEY` means the signing secret holds a key other than `.github/release-signing-key.asc`; `EXPKEYSIG` means the key has expired. Fix the secret or the key file before the final release. When the release key is rotated, replace `.github/release-signing-key.asc` with the new public key in the same change.
- **`coordinates carry version X, expected Y`** — the published `VERSION` disagrees with the version being probed. `validate` catches an `rc_ver` mismatch before publication, so reaching this points at the release branch moving between `validate` and `publish` rather than at a bad `rc_ver`. Check the branch's history for a push mid-release.

### Demo app tests fail after push

Possible causes:

1. **Maven Central propagation delay** — artifacts not yet visible. Wait 10–15 minutes, then re-run the failed jobs with `gh run rerun <RUN_ID> --repo airbnb/viaduct --failed`.
2. **Stale Copybara content** — check the workflow output for warnings.

### Release branch cut from the wrong commit

If nothing has been published from the branch yet, delete it and cut it again from the right commit:

```bash
git checkout main
git branch -D release/v${RELEASE_VER}
git push origin --delete release/v${RELEASE_VER}
export CUT_SHA="<correct-full-sha>"
git checkout -b release/v${RELEASE_VER} ${CUT_SHA}
```

Then continue at Step 2's **Set the release version**.

> Only delete a remote branch if no artifacts have been published from it. After an RC has been published, use the [re-cut](#another-rc) path instead.
