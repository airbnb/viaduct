# Adding a New Demo App

There are two categories of demo apps:

- **Published** — pushed to `viaduct-dev/*` repos via Copybara and tested against published artifacts. These are: `cli-starter`, `jetty-starter`, `ktor-starter`, `micronaut-starter`, `starwars`.
- **CI-only** — built and tested in CI but not published to a standalone repo. These are: `spring-starter`.

To add a new demo app to the **publishing** workflow:

1. **Add the demo app to the Copybara config** (`.github/copydemoapps/copy.bara.sky`):
   ```python
   DEMO_APPS = [
       "cli-starter",
       "jetty-starter",
       "ktor-starter",
       "micronaut-starter",
       "starwars",
       "your-new-app",  # Add here
   ]
   ```

2. **Add the demo app to the push workflow** (`.github/workflows/push-demoapps.yml`):

   In the `push` job matrix:
   ```yaml
   matrix:
     demoapp: [cli-starter, jetty-starter, ktor-starter, micronaut-starter, starwars, your-new-app]
   ```

3. **Add the demo app to the check workflow** (`.github/workflows/check-published-demoapps.yml`):

   In the `test` job matrix:
   ```yaml
   matrix:
     demoapp: [cli-starter, jetty-starter, ktor-starter, micronaut-starter, starwars, your-new-app]
   ```

4. **Add the demo app to the CI check workflow** (`.github/workflows/demoapps-ci-check.yml`):

   In the `test-starters` job matrix:
   ```yaml
   matrix:
     demoapp: [cli-starter, jetty-starter, ktor-starter, micronaut-starter, spring-starter, your-new-app]
   ```

5. **Ensure the demo app has proper structure**:
   - Located in `demoapps/your-new-app/`
   - Has a `gradle.properties` with `viaductVersion` property
   - Builds independently with `./gradlew build`

6. **Create the destination repository** in the `viaduct-dev` organization on GitHub
