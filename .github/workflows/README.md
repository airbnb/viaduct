# GitHub Workflows

This directory contains GitHub Actions workflows for the Viaduct OSS project.

## Workflows

### prepare-release-candidate.yml

**Purpose:** Automates step #2 of the release process from the RUNBOOK.md.

**What it does:**
1. Increments the version from `0.X.0-SNAPSHOT` to `0.(X+1).0-SNAPSHOT`
2. Updates the VERSION file
3. Updates all `gradle.properties` files in the project and demo apps
4. Generates a changelog using commits since the last release
5. Creates a new branch named `candidate/v0.(X+1).0`
6. Opens a pull request with the version bump and changelog

**Triggers:**
- **Manual trigger:** Can be run manually via GitHub Actions UI with optional version override
- **Scheduled:** Automatically runs every Monday at 9 AM UTC (prior to Wednesday team meeting)

**Usage:**

#### Automatic (Scheduled)
The workflow will run automatically every Monday morning, creating a release candidate PR for review at the Wednesday meeting.

#### Manual Trigger
To manually trigger the workflow:

1. Go to the [Actions tab](https://github.com/airbnb/viaduct/actions/workflows/prepare-release-candidate.yml)
2. Click "Run workflow"
3. Configure options:
   - **version_override** (optional): Specify a custom version (e.g., `0.8.0`) or leave blank to auto-increment
   - **update_existing** (optional): Check this to update an existing release candidate
4. Click "Run workflow"

**Version Override:**
- If provided: Uses the specified version (e.g., `0.8.0` becomes `0.8.0-SNAPSHOT`)
- If not provided: Auto-increments the minor version (e.g., `0.7.0-SNAPSHOT` → `0.8.0-SNAPSHOT`)

**Updating an Existing Release Candidate:**
If you need to add more commits to an existing release candidate (e.g., cherry-picked fixes):
1. Make sure the new commits are merged to `main`
2. Re-run the workflow with the same version
3. **Enable the "update_existing" checkbox**
4. The workflow will:
   - Regenerate the changelog with all commits up to current `HEAD`
   - Force push the updated branch
   - Update the PR description with the new changelog

**Output:**
- Creates a new branch: `candidate/v0.(X+1).0`
- Opens a PR with:
  - Title: "Release Candidate: Bump version to 0.(X+1).0-SNAPSHOT"
  - Changelog of all changes since last release
  - Checklist for review and approval
  - Label: `release-candidate`

**Error Handling:**
- If the candidate branch already exists on remote and `update_existing` is not enabled, the workflow will fail with instructions to either enable the option or delete the branch
- All gradle.properties files are verified after update
- Force push is only used when explicitly updating an existing candidate

**Next Steps:**
After the workflow completes, the release manager should:
1. Review the generated PR and changelog
2. Present at the Wednesday OSS team meeting
3. Follow steps 3-12 in the RUNBOOK.md to complete the release
