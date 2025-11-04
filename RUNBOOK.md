# Viaduct OSS Infrastructure Runbook

This document explains how to administer the infrastructure used by the
Viaduct OSS project.

## Github

Administration is handled by Airbnb's open source committee.

### CI

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
      - Demo apps are published only on **release branches** (format: `release/v[major].[minor].[patch]`)
      - Each demo app must build successfully and have a `viaductVersion` that matches the release version
      - **Publishing with GitHub Actions (Recommended)**:
        1. Ensure you're on a release branch (e.g., `release/v0.7.0`)
        2. Go to the Actions tab in GitHub at `airbnb/viaduct`
        3. Select "Publish Demo Apps" workflow
        4. Click "Run workflow" and select the release branch

        The workflow will:
        - Validate each demo app in parallel (version check + build)
        - Update each demo app's `gradle.properties` with the release version
        - Use Copybara to sync each demo app to its external repository

      - **Local validation before publishing**:
        ```shell
        # Validate a specific demo app
        python3 ./.github/scripts/validate_demoapp.py starwars
        ```

      - **Manual copybara execution (Advanced)**:
        ```shell
        # Ensure SSH keys are configured
        ssh -T git@github.com

        # Run copybara manually from projects/viaduct/oss
        tools/copybara/run migrate \
          .github/copybara/copy.bara.sky \
          airbnb-viaduct-to-starwars \
          --git-destination-url=git@github.com:viaduct-graphql/starwars.git \
          --git-committer-email=viabot@ductworks.io \
          --git-committer-name=ViaBot \
          --force
        ```

      - **Demo apps published**:
        - `starwars` → `viaduct-graphql/starwars`
        - `cli-starter` → `viaduct-graphql/cli-starter`
        - `ktor-starter` → `viaduct-graphql/ktor-starter`

      - **Troubleshooting**:
        - **Version mismatch**: Update `viaductVersion` in demo app's `gradle.properties` to match branch version
        - **Build failure**: Test locally with `cd demoapps/starwars && ./gradlew clean build`
        - **Not on release branch**: Create and switch to a release branch (format: `release/v[major].[minor].[patch]`)
        - **Authentication errors**: Verify GitHub secrets are configured (`VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN`)
        - **SSH authentication**: Ensure keys are in GitHub and agent is running (`ssh-add ~/.ssh/id_rsa`)

13. Release manager manually publishes the Github release.

### Adding a New Demo App

To add a new demo app to the publishing workflow:

1. **Add the demo app to the Copybara config** (`.github/copybara/copy.bara.sky`):
   ```python
   DEMO_APPS = [
       "starwars",
       "cli-starter",
       "ktor-starter",
       "your-new-app",  # Add here
   ]
   ```

2. **Add the demo app to the workflow** (`.github/workflows/publish-demoapps.yml`):

   In the `validate` job matrix:
   ```yaml
   matrix:
     demoapp: [starwars, cli-starter, ktor-starter, your-new-app]
   ```

   In the `publish` job matrix:
   ```yaml
   matrix:
     include:
       - name: starwars
         repo: viaduct-graphql/starwars
       - name: your-new-app
         repo: viaduct-graphql/your-new-app
   ```

3. **Ensure the demo app has proper structure**:
   - Located in `demoapps/your-new-app/`
   - Has a `gradle.properties` with `viaductVersion` property
   - Builds independently with `./gradlew build`

4. **Create the destination repository** in the `viaduct-graphql` organization on GitHub

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
