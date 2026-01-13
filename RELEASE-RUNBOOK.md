# Cutting a Viaduct Release

## Overview

The release process is straight forward. Assume the most recent published release was `0.5.0`, which means the `VERSION` on the main branch will be `0.6.0-SNAPSHOT`. Each of these steps will be described in detail, but in broad strokes:

* At release time, open a PR that bumps the version on the main branch to the the `SNAPSHOT` for the _next_ release. In our example this would be `0.7.0-SNAPSHOT`.

* When this version bump is merged, the SHA just before it becomes the branch point for the next release (for `0.6.0`) in our example. A release branch created from that branch point, and the `VERSION` in that branch loses `-SNAPSHOT` (e.g., `0.6.0-SNAPSHOT` becomes `0.6.0` in the release branch).

* A script is run to generate the changelog for the release. The output of this script often requires slight editing for clarity and consistency. The changelog is reviewed by the team.

* The release branch is validated. This is an important but somewhat tedious step.

* Assuming all is well with the release branch, its artifacts are then published to the Maven Central and the Gradle Plugin Portal.

* Next, the "standalone" copies of the demo apps (in https://github.com/viaduct-graphql) are updated to match the release. This step has been fagile, so more validation is required to assure correctness.

* Finally, the release is published on the [Viaduct Releases](https://github.com/airbnb/viaduct/releases) page (don't forget this step!).


### Repository Context

Different steps in the release process happen in different places:

| Step | Location | Example |
|------|----------|---------|
| Version bump PR | Your personal fork | Fork → PR to main |
| Release branch creation | Public `airbnb/viaduct` repo | Direct push to public repo |



### Viaduct Versioning

In the root directory of the Viaduct repository is in a file called `VERSION`. As mentioned above:

- **On main branch:** Always contains a `-SNAPSHOT` version (e.g., `0.7.0-SNAPSHOT`)
- **On release branches:** Contains the actual release version (e.g., `0.7.0`)

**Example flow:**

1. Main branch has: `0.7.0-SNAPSHOT`
2. During release week:
   - Main is bumped to: `0.8.0-SNAPSHOT`
   - Release branch `release/v0.7.0` is created with VERSION: `0.7.0`

#### Demo App Versions

Demo applications have `gradle.properties` files with a `viaductVersion` property. This property **must always match** the
root `VERSION` file.  To help keep these versions matching, we have a Gradle task to synchronize them:

```bash
./gradlew syncDemoAppVersions
```

This Gradle task updates all demo app `gradle.properties` files to match the `VERSION` file. So the update procedure is to update `VERSION` and then run this task to synchronize.


## Prerequisites for Release Managers

### Required Tools

**GitHub CLI (`gh`)**

Install this if you haven't already (e.g., `brew install gh`).

To verify installation:

```bash
gh --version
```

**Python 3**

Python should be pre-installed on macOS.  To verify:

```bash
python3 --version
```

### Required Access

Verify you have access to:

- Airbnb GitHub organization (check: [https://github.com/orgs/airbnb/people](https://github.com/orgs/airbnb/people))
- Viaduct-GraphQL GitHub organization ([https://github.com/orgs/viaduct-graphql/people](https://github.com/orgs/viaduct-graphql/people))
- Gradle Plugin Portal `viaduct-maintainers` account (check: https://plugins.gradle.org/u/viaduct-maintainers)
- 1Password vault with `viaductbot` credentials (for Sonatype)

If you're missing any access, contact your team lead before release day.


### Setup (one-time)

**Verify SSH access to GitHub**

```bash
ssh -T git@github.com
```

Expected output: `Hi <username>! You've successfully authenticated, but GitHub does not provide shell access.`

If this fails:

- Ensure you have SSH keys set up: https://docs.github.com/en/authentication/connecting-to-github-with-ssh
- Add your key to ssh-agent: `ssh-add ~/.ssh/id_rsa`

**Authenticate GitHub CLI**

```bash
gh auth login
```

- Select: `GitHub.com`
- Select: `HTTPS`
- Authenticate via browser when prompted


**Clone public Viaduct repository:**

Clone to a separate directory (not inside Treehouse):

```bash
git clone git@github.com:airbnb/viaduct.git ~/repos/viaduct-public
```

**[Optional] Set default repository**

This makes `gh` commands default to `airbnb/viaduct` so you don't need to specify `--repo` every time.

Run from within the cloned directory:

```bash
cd ~/repos/viaduct-public
gh repo set-default airbnb/viaduct
# When prompted, select: airbnb/viaduct
```

**Why this is useful:** When you run commands like `gh workflow run` or `gh release list`, they'll automatically use `airbnb/viaduct` instead of requiring `--repo airbnb/viaduct` every time.

> Note: Some changes will be pushed to your fork of the OSS repo and some will go straight to the OSS repo. You can
> either use the same local clone and switch the remote appropriately with
> `git remote set-url origin git@github.com:YOUR_GH_USERNAME/viaduct.git` or you can have two separate local clones.

## Detailed Release Process

Viaduct follows a weekly release cadence.

### 1) Release manager

During Monday's Viaduct team meeting, we will pick the release manager for the week.  "You" in the rest of this document is the release manager.

### 2) Bump version in main branch

> Note: This step should be done in your personal fork of the public repo, and the PR submitted through the standard
> PR process.  Be sure to sync your fork to the public repo before performing this step. It takes at least a couple of
> hours to get through this. So, either start Tuesday or early on Wednesday.

> Note: Prefix your PR and commit with `chore: ` to be compliant with our conventional commit message

Prior to the Wednesday Viaduct team meeting, you will create a PR in the OSS repo (but from your personal fork) that bumps the version on the main branch from `0.X.0-SNAPSHOT` to `0.(X+1).0-SNAPSHOT`. This is done by editing the `VERSION` file and then running:

```bash
./gradlew syncDemoAppVersions
```

to update the `gradle.properties` files of the demoapps to match.  You can verify the changes by running:

```bash
git diff .
```

You should see that the `VERSION` file has changed a the project root and the `gradle.properties` files have changed for each of the demo apps.

> Use the normal PR merge process to merge this OSS PR. (pull-me)

### 3) Make release branch

Once this PR is approved and merged:

- Create a branch off the SHA just before the version bump. This branch should be named `release/v0.X.0`.
- Make sure that the branch name contains the `v` before the version or subsequent steps might break.
- In this branch, edit the `VERSION` file to contain `0.X.0`

and then run:

> Note: Make sure that the branch name contains the `v` before the version or subsequent steps might break.

```bash
./gradlew syncDemoAppVersions
```

to update the demoapps to match (you can use `git diff` as above to verify).  Push this branch directly to the public Viaduct github.com repository.

### 4) Validate build

Trigger comprehensive testing across all supported environments by running:

```shell
gh workflow run ".github/workflows/trigger-all-builds.yml" \
--ref release/v0.X.0 \
-f reason="Testing release candidate v0.X.0"
```

This will trigger builds on all supported combinations:

- OS: ubuntu-latest, macos-latest, macos-15-intel
- Java: 11, 17, 21

Monitor the triggered builds and verify all 9 combinations pass successfully.


### 5) Validate Demo Apps

We've had problems in the past with the published demo apps not working correctly with our published artifacts. If we discover a problem post-publication, it often requires that we restart the entire release process. To avoid that, in this step we use the Maven Local Cache to test that the demo apps against the published artifacts (the validation above tests them as included builds).

#### 5a) Clean your local Maven cache

Remove existing Viaduct artifacts to ensure fresh build:

```bash
rm -rf ~/.m2/repository/com/airbnb/viaduct
```

#### 5b) Publish to Maven local

```bash
cd ~/repos/viaduct-public    # Or wherever your public viaduct clone is
git checkout release/v0.X.0  # Your release branch
VIADUCT_PLUGIN_SNAPSHOT=false ./gradlew publishToMavenLocal
```

This puts the build artifacts in `~/.m2/repository/` (setting the `VIADUCT_PLUGIN_SNAPSHOT` variable is important, otherwise `-SNAPSHOT` will be appended to the locally-published version number).

#### 5c) Configure demo apps to use Maven local

Update all demo app settings.gradle.kts to check mavenLocal() first:

```bash
for i in demoapps/*/settings.gradle.kts; do sed -i '' 's/^\([ ]*\)repositories {/\1repositories { mavenLocal()/g' "$i"; done
```

Note: This sed command adds `mavenLocal()` after `repositories {`. The formatting won't be perfect (no newline), but it works.

#### 5d) Test each demo app

```bash
for i in demoapps/*; do (cd $i; ./gradlew clean test --scan); done
```

The `--scan` flag generates a build scan URL (you'll have to accept the terms of service). Open it and verify dependencies are being pulled from `~/.m2/repository` (local) and not from remote repositories, which you can find as noted in this screenshot:
![build-scan UI scrrenshot](buildscan-screenshot.png)

#### 5e) Restore demo app settings

```bash
git restore demoapps
```

**Troubleshooting:**

- *Build fails with signing error:* This should not happen after the signing fix. If it does, ensure you're using the updated tools/build.gradle.kts
- *Dependencies not from local:* Check build scan, ensure `mavenLocal()` was added correctly
- *Tests fail:* Investigate if it's a real issue or environmental problem

### 6) Generate Changelog

Prior to the Wednesday team meeting, you should generate and review the changelog.

#### 6a) Generate changelog

Assuming you're releasing `v0.7.0`:

```bash
cd ~/repos/viaduct-public    # Or wherever your public viaduct clone is
git checkout release/v0.7.0  # Your release branch

.github/scripts/generate_changelog.py origin/release/v0.6.0 HEAD > /tmp/release-v0.7.0-changelog.md
```

#### 6b) Clean up the output

The generated changelog may include:

- Bookkeeping commits at the start (e.g., "Bump version to X.Y.Z-SNAPSHOT")
- Bookkeeping commits at the end (e.g., "Set version to X.Y.Z")
- Unclear or overly technical commit messages

Edit the changelog to:

- Remove version bump commits
- Clarify cryptic commit messages
- Group related changes if helpful
- Ensure it's understandable to users, not just developers

#### 6c) Share with team

- Post the edited changelog in the Viaduct OSS Slack channel
- Ask for feedback: "Proposed changelog for v0.7.0 release. Please review before Wednesday meeting."
- Note any concerns or suggested edits
- At the Wednesday OSS team meeting present the changelog and lead a discussion to reach approval on the week's release.

#### 6d) Keep the file handy

You'll paste this into the GitHub release page in Step 11.

### 7) Confirm release

At the Wednesday OSS meeting the team will confirm that the release is ready. If necessary, based on team discussion, the release manager may wait for an in-flight change to land and will cherry-pick the change into the release branch once it is merged into the main branch.

### 8) Publish the artifacts

Before triggering the workflow, verify you're in the correct repo + branch and that your working tree is clean:

```bash
gh repo view --json nameWithOwner
git branch --show-current
git status --porcelain
```

Expected :
• nameWithOwner is airbnb/viaduct
• branch is release/v0.X.0
• git status --porcelain is empty

Then, trigger the release workflow:

```
gh workflow run ".github/workflows/release.yml" \
-f publish_snapshot=false \
-f previous_release_version=0.6.0 \
-f release_version=0.7.0
```

**Don't mix up `previous_release_version` and `release_version`**

> Note: If the above command fails and it is appropriate, you can use the `-f skip_check=true` flag to avoid failures
> because of detekt rule violations.

This workflow will:

- Package release artifacts using Gradle.
- Publish plugin artifacts to the Gradle Plugin Portal.
- Stage a deployment to Sonatype.
- Pushes a `v0.X.0` tag to Github.
- Create a draft Github release.

### 9) Verify publications

Log in to [Sonatype Maven Central](https://plugins.gradle.org/u/viaduct-maintainers) and the [Gradle Plugin Portal](https://plugins.gradle.org/u/viaduct-maintainers) to verify the artifacts are live (credentials in shared 1Password vault).

### 10) Publish and verify standalone apps

Once the artifacts are published, we need to update the standalone copies of the standalone apps to agree with the new release.

- `starwars` → `viaduct-graphql/starwars`
- `cli-starter` → `viaduct-graphql/cli-starter`
- `ktor-starter` → `viaduct-graphql/ktor-starter`

**It's important to do this on the release branch!**

We do this with a copybara script. For each demoapp run:



```bash
mkdir -p .github/copybara
cp ~/repos/treehouse/projects/viaduct/oss/.github/copybara/copy.bara.sky .github/copybara/copy.bara.sky

for i in cli-starter ktor-starter starwars; do
  tools/copybara/run migrate \
    .github/copybara/copy.bara.sky \
    airbnb-viaduct-to-$i \
    release/v0.X.0 \
    --git-destination-url=git@github.com:viaduct-graphql/$i.git \
    --git-committer-email=viabot@ductworks.io \
    --git-committer-name=viabot \
    --force
  done
```

You should have a local clone of each of these demoapp repository. After running updating their repos, pull the update to your local clone and verify that they pass their tests. For each demoapp:

```bash
cd ~/repos/starwars # Or wherever your public clone is
git pull
./gradlew clean test --scan
```

Verify in the build-scan that the correct release artifacts have been used.

### 11) ⚠️ Publish the GitHub Release

**This step has been skipped in previous releases - please don't.**

Why this matters:

- GitHub releases page is often the first place users check for new versions
- "Latest" badge helps users quickly find the current version
- Changelog provides important migration information
- Links from external sites often point to the releases page
  Once artifacts are published to Maven Central and Gradle Plugin Portal, and demo apps are verified, make the release official on GitHub.

To publish:

#### 11a) Navigate to releases page

```bash
open https://github.com/airbnb/viaduct/releases
```

#### 11b) Find the draft release

- You should see a draft release for `v0.X.0` created by the GitHub Action in Step 9
- Click "Edit" on this draft

#### 11c) Add the changelog

- Paste the changelog from step 6 it into the description field of the release
- Preview the markdown to check formatting

#### 11d) Publish the release

Review one final time:

- Version tag is correct (e.g., `v0.7.0`)
- Target is the correct release branch
- Changelog is complete and well-formatted
- ✅ **Ensure that "Set as the latest release" is checked** (very important!)
- Click **"Publish release"**

#### 11e) Verify publication

- Visit [https://github.com/airbnb/viaduct/releases](https://github.com/airbnb/viaduct/releases)
- Confirm your release shows as "Latest"
- Share the release link in Slack: `https://github.com/airbnb/viaduct/releases/tag/v0.X.0`

