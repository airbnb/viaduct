# Viaduct OSS Infrastructure Runbook

This document explains how to administer the infrastructure used by the
Viaduct OSS project.

## Github

Administration is handled by Airbnb's open source committee.

### CI

We use Github Actions to run Viaduct's public [CI jobs](https://github.com/airbnb/viaduct/actions).

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
     include:
       - name: starwars
         repo: viaduct-dev/starwars
       - name: your-new-app
         repo: viaduct-dev/your-new-app
   ```

3. **Ensure the demo app has proper structure**:
   - Located in `demoapps/your-new-app/`
   - Has a `gradle.properties` with `viaductVersion` property
   - Builds independently with `./gradlew build`

4. **Create the destination repository** in the `viaduct-dev` organization on GitHub
