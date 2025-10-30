# Viaduct OSS Infrastructure Runbook

This document explains how to administer the infrastructure used by the
Viaduct OSS project.

## Github

Administration is handled by Airbnb's open source committee.

## CircleCI

We use Github Actions to run Viaduct's public [CI
jobs](https://github.com/airbnb/viaduct/actions).

## Gradle Plugin Portal

Plugins are published via the `viaduct-maintainers` account owned by
Airbnb. https://plugins.gradle.org/u/viaduct-maintainers

## Maven Central/Sonatype

Access to Airbnb's Sonatype namespace is controlled via Airbnb's Github
organization. Only members of the Airbnb Github organization can access
the namespace.

## Copybara

Viaduct has dual homes: Github and Airbnb's internal monorepo. We use
[Copybara](https://github.com/google/copybara) to sync changes between
the two source trees. Copybara runs on internal Airbnb infrastructure
and is not accessible to outside contributors.

## How To

### Publishing a new release

Viaduct follows a weekly release cadence.

1. During Monday's Viaduct team meeting, we will pick the release manager for the week.
2. Prior to the Wednesday Viaduct team meeting, the release manager creates a branch in Github called
`candidate/v0.(X+1).0` where `X` is the version being released. The release manager opens a pull request
against the main branch, bumping `VERSION` from `0.X.0-SNAPSHOT` to `0.(X+1).0-SNAPSHOT` and updates all the
demo app `gradle.properties` files to match.
3. Once this PR is approved and merged, the release manager creates a branch off the SHA just before this version bump.
This is the week's release candidate branch. This branch is called `release/vX.Y.Z`
4. The release manager triggers comprehensive testing across all supported environments by running:

    ```shell
    gh workflow run ".github/workflows/trigger-all-builds.yml" \
    --ref release/vX.Y.Z \
    -f reason="Testing release candidate v0.X.0"
    ```

    This will trigger builds on all supported combinations:
    - OS: ubuntu-latest, macos-latest, macos-15-intel
    - Java: 11, 17, 21

    Monitor the triggered builds and verify all 9 combinations pass successfully before the Wednesday meeting.

5. At the Wednesday OSS team meeting, the release manager presents a proposed changelog for the release candidate branch and leads a discussion to reach approval on the week's release.
6. If the team agrees on releasing the proposed change set, the release manager proceeds with the release.
    - If necessary based on team discussion, the release manager may wait for an in-flight change to land and will cherry-pick the change into the release branch once it is merged into the main branch.
7. In the release candidate branch, the release manager bumps the `VERSION` file to the desired release version and updates the demo app gradle.properties files to match the version file.
8. The release manager manually invokes a Github Action that uses `HEAD` of the release candidate branch via

```
gh workflow run ".github/workflows/release.yml" \
--ref main \
-f release_version=0.7.0 \
-f previous_release_version=0.6.0 \
-f publish_snapshot=false
```

Update parameters as needed. This workflow will:
  - Package release artifacts using Gradle.
  - Publish plugin artifacts to the Gradle Plugin Portal.
  - Stage a deployment to Sonatype.
  - Pushes a `vX.Y.Z` tag to Github.
  - Create a draft Github release with changelog.

9. Verify that deployments to Sonatype are successfully validated. Log in as `viaductbot` (credentials in shared 1Password vault).
10. Release manager reviews draft Github release and artifacts published to Sonatype and Gradle. This includes reviewing the changelog.
    - If the release manager rejects the release, start over with an incremented patch version. Once artifacts are published, they may not be changed.
11. If the release manager is satisfied, manually publish the Sonatype deployments. Make sure to publish all three deployments. Publishing takes 5-10 minutes.
12. Release manager must verify standalone demo apps against the newly published versions of artifacts in Maven Central and Gradle Plugin Portal.
      - At their discretion, the release manager uses copybara to update the standalone demo apps. From `projects/viaduct/oss` in Treehouse:

        ```shell
        export IS_RELEASE=true
        export PUBLISHED_VERSION=0.X.0
        python3 _infra/scripts/publish_all_demoapps.py
        ```

13. Release manager manually publishes the Github release.

### Inbound Pull Request Process

Viaduct's source of truth is in Airbnb's monorepo, Treehouse. The Github repository
at `airbnb/viaduct` is a mirror of the Treehouse source subtree. When a pull request is
opened against the Github repository and approved, the changes must first be applied to
Treehouse:

1. External contributor opens a pull request against `airbnb/viaduct`.
2. Viaduct maintainer reviews and approves the pull request in Github.
3. Viaduct maintainer applies the changes to Treehouse using Copybara.
    - **Option 1: Using pull-me (recommended, requires gh CLI)**:
      - **One-time setup**:
        - Authenticate with GitHub CLI: `gh auth login` (for github.com) and `gh auth login --hostname git.musta.ch` (for internal GHE)
        - Configure your GitHub username: `yak config githubUsername YOUR_GITHUB_USERNAME`
      - Run `yak script projects/viaduct/oss:pull-me` to automatically pull your latest PR, or
      - Run `yak script projects/viaduct/oss:pull-me <PR_NUMBER>` to pull a specific PR
      - Use `--override` flag to re-run with force push: `yak script projects/viaduct/oss:pull-me --override <PR_NUMBER>`
    - **Option 2: Using manual-inbound (uses tokens)**:
      - **One-time setup**: Configure your GitHub username:
        ```shell
        yak config githubUsername YOUR_GITHUB_USERNAME
        ```
      - Run `yak script projects/viaduct/oss:manual-inbound` to automatically pull your latest PR, or
      - Run `yak script projects/viaduct/oss:manual-inbound <PR_NUMBER>` to pull a specific PR
      - The script will automatically set up GitHub tokens if needed on first run
    - Stamp and merge the change in Treehouse.
4. Treehouse CI will automatically update the Github repo and close the inbound PR.
