# Demo App Publishing Guide

This document describes how to publish demo apps from the `airbnb/viaduct` repository to the `viaduct-graphql` organization repositories.

## Overview

Demo apps are published only on **release branches** (format: `release/v[major].[minor].[patch]`). Each demo app must:
1. Build successfully when run individually (e.g., `cd demoapps/starwars && ./gradlew build`)
2. Have a `viaductVersion` in its `gradle.properties` that matches the release branch version

The publishing process uses the [Google Copybara GitHub Action](https://github.com/olivr/copybara-action) to sync the demo app code to external repositories on GitHub.

## Prerequisites

### For CI (GitHub Actions)
- **JDK 21**: Required for building the demo apps
- **Python 3.13**: Required for validation scripts
- **GitHub Secrets**:
  - `VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN`: Personal access token for pushing to viaduct-graphql org
  - `VIADUCT_GRAPHQL_DEPLOY_KEY`: SSH deploy key for authentication (optional, used as fallback)

### For Local Testing
- **Copybara**: Must be installed and accessible via `yak script tools/copybara:run`
- **JDK 21**: Required for building the demo apps
- **Python 3.13**: Required for validation
- **SSH Keys**: Configured for GitHub access to viaduct-graphql org

## Publishing with GitHub Actions (Recommended)

The workflow is available at `.github/workflows/publish-demoapps.yml` and must be triggered manually.

### Steps:
1. Ensure you're on a release branch (e.g., `release/v0.7.0`)
2. Go to the Actions tab in GitHub
3. Select "Publish Demo Apps" workflow
4. Click "Run workflow"
5. Select the branch (should be your release branch)
6. Click "Run workflow"

The workflow will:
- Validate each demo app in parallel (version check + build)
- Extract the version from the branch name automatically
- Update each demo app's `gradle.properties` with the release version
- Use the Google Copybara Action to sync each demo app to its external repository

## Local Testing and Validation

You can validate demo apps locally before publishing, or manually run copybara for testing.

### Validate a Demo App

```bash
# Switch to the release branch
git checkout release/v0.7.0

# Validate a specific demo app
python3 ./.github/scripts/validate_demoapp.py starwars
```

The validation script checks:
1. **Branch validation**: Verifies you're on a `release/v*` branch
2. **Version matching**: Checks that the demo app's `viaductVersion` matches the branch version
3. **Individual build**: Builds the demo app in isolation to ensure it works standalone

### Manual Copybara Execution (Advanced)

If you need to run copybara manually for testing:

1. **Ensure SSH keys are configured:**
   ```bash
   ssh -T git@github.com
   ```
   You should see a success message from GitHub.

2. **Switch to the release branch and update version:**
   ```bash
   git checkout release/v0.7.0
   # Update the demo app's gradle.properties manually if needed
   ```

3. **Run copybara manually:**
   ```bash
   tools/copybara/run migrate \
     .github/copybara/copy.bara.sky \
     airbnb-viaduct-to-starwars \
     --git-destination-url=git@github.com:viaduct-graphql/starwars.git \
     --git-committer-email=viabot@ductworks.io \
     --git-committer-name=ViaBot \
     --force
   ```

**Note**: Manual copybara execution uses SSH for GitHub authentication. The workflow uses HTTPS with an access token.

## Demo Apps Published

The following demo apps are published:
- `starwars` → `viaduct-graphql/starwars`
- `cli-starter` → `viaduct-graphql/cli-starter`
- `ktor-starter` → `viaduct-graphql/ktor-starter`

## Workflow Details

### Validation Phase
The workflow runs validation for all demo apps in parallel:
- Checks that you're on a release branch
- Verifies the `viaductVersion` matches the branch version
- Builds each demo app independently to ensure it works standalone

### Publishing Phase
After validation succeeds, the workflow publishes each demo app in parallel:
- Extracts version from the branch name (e.g., `release/v0.7.0` → `0.7.0`)
- Updates the demo app's `gradle.properties` with the release version
- Runs the Copybara GitHub Action to sync files to the external repository

The Copybara Action handles:
- Fetching the latest code from both repositories
- Applying transformations (moving files from `demoapps/<name>/` to root)
- Committing and pushing to the external repository

## Troubleshooting

### Version mismatch error during validation
```
❌ Version mismatch!
   Expected version: 0.7.0
   Demo app version: 0.7.0-SNAPSHOT
```

**Solution**: Update the `viaductVersion` in the demo app's `gradle.properties` to match the branch version before running the workflow:
```bash
cd demoapps/starwars
# Edit gradle.properties to set viaductVersion=0.7.0
```

### Build failure during validation
```
❌ Build failed
```

**Solution**: The demo app must build successfully on its own. Test it locally:
```bash
cd demoapps/starwars
./gradlew clean build
```

Fix any build errors before attempting to publish.

### Not on a release branch
```
❌ Not on a release branch. Current branch: main
   Expected branch format: release/v[major].[minor].[patch]
```

**Solution**: Create and switch to a release branch before running the workflow:
```bash
git checkout -b release/v0.7.0
git push -u origin release/v0.7.0
```

### Authentication errors in CI

**Problem**: Copybara action fails with authentication errors.

**Solution**: Ensure GitHub secrets are properly configured:
- `VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN`: Must have `repo` scope and write access to viaduct-graphql org
- `VIADUCT_GRAPHQL_DEPLOY_KEY`: (Optional) SSH deploy key for the target repositories

### SSH authentication for local testing

If running copybara manually, ensure your SSH keys are configured:
```bash
ssh -T git@github.com
# Should return: Hi username! You've successfully authenticated...
```

If SSH authentication fails, check:
- SSH keys are added to your GitHub account
- SSH agent is running: `eval "$(ssh-agent -s)"`
- Key is added to agent: `ssh-add ~/.ssh/id_rsa` (or your key path)

## Scripts Overview

- **`validate_demoapp.py`**: Validation script for a single demo app
  - Accepts demo app name as argument (e.g., `starwars`)
  - Verifies you're on a release branch (format: `release/v[major].[minor].[patch]`)
  - Checks that `viaductVersion` in `gradle.properties` matches the branch version
  - Builds the demo app independently to ensure it works standalone
  - Used by the GitHub Actions workflow in the validation phase

## Copybara Configuration

All demo apps use a **single shared Copybara configuration** located at:
`.github/copybara/copy.bara.sky`

This unified config dynamically creates workflows for each demo app defined in the `DEMO_APPS` list. Each workflow:
- Syncs files from `demoapps/<name>/` in the source repo
- Moves them to the root directory in the destination repo
- Excludes build artifacts, IDE files, and database files
- Uses `SQUASH` mode to combine all changes into a single commit

### Authentication

The GitHub Actions workflow uses the **Copybara Action** which handles authentication:

- **GitHub Actions**: Uses HTTPS with `VIADUCT_GRAPHQL_GITHUB_ACCESS_TOKEN`
- **Fallback**: Can use `VIADUCT_GRAPHQL_DEPLOY_KEY` (SSH) if token is not available
- **Manual/Local**: Uses SSH authentication with your configured keys

## Adding a New Demo App

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
     demoapp:
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

That's it! The workflow will automatically include the new demo app in validation and publishing.
