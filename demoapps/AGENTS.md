This directory contains a number of end-to-end examples of services that embed Viaduct, with `starwars/` in particular being the most complete such example (using almost every feature of Viaduct).  These demo applications serve two purposes: they serve as illustrative to help newcommers to Viaduct (including agents) to better understand how Viaduct applications work, and they serve as important integration tests, not just for the Viaduct runtime but for it's build- and publishing tooling as well.

In this role as integration tests, the demoapps can be run of two ways.  The simplest is to run them as included builds.  To do this, from the top-level Viaduct OSS directory, simply run:
```shell
./gradlew :ktor-starter
```
for the `ktor` starter app or
```shell
./gradlew :starwars
```
for the starwars app, and so forth.

We also often run the demoapps using the Maven local cache, which is a true end-to-end test of Viaduct: confirming that applications **outside** of the Viaduct monorepo can consume our published artifacts and build successfully using them.  When performing these tests, we do **not** use the default Maven local cache location (`~/.m2`), for two reasons: (1) we often run multiple agents on the same host, and we want to make sure they don't interfere with each other, and (2) we have often been tricked by stale cache results into thinking a test passed when it fact something (typically in the publication chain) is broken.

Thus, we use the `maven.repo.local` flag to create a fresh cache for each end-to-end test run.  In particular, we use directories named `/tmp/mlc/m2-blah/` for this purpose, where "blah" is actually a **random**, four-character identifier drawn from the characters `[0-9a-z]`.  This random identifier is important to avoid conflicts.

## Running isolated Maven-local tests

### Step 1: Create a fresh, isolated local repository

Generate a random 4-character identifier and create the directory:

```shell
MLC="/tmp/mlc/m2-$(head -c 100 /dev/urandom | tr -dc '0-9a-z' | head -c 4)"
mkdir -p "$MLC"
```

### Step 2: Publish to the isolated repository

From the top-level Viaduct OSS directory, run clean followed by publish as **separate** invocations (combining them in one Gradle call causes race conditions where clean removes outputs needed by later tasks):

```shell
./gradlew clean --no-daemon
./gradlew publishToMavenLocal -PpublishMinimal -Dmaven.repo.local="$MLC" --no-daemon
```

The `clean` step is important: `processResources` in the Gradle plugins uses `expand()` to stamp the current version into `viaduct-plugin-version.properties`, but Gradle's incremental build may not invalidate it when only the `VERSION` file changes.  Running `clean` avoids stale version artifacts.

The `-PpublishMinimal` flag skips Dokka and sources-jar generation to speed things up.  It still publishes all runtime artifacts including the Java API.

### Step 3: Run a demoapp test against the isolated repository

Navigate to the demoapp directory and run its tests with `USE_MAVEN_LOCAL=true` and the custom repository path:

```shell
cd demoapps/ktor-starter
USE_MAVEN_LOCAL=true ./gradlew test -Dmaven.repo.local="$MLC" --no-daemon
```

Replace `ktor-starter` with whichever demoapp you want to test (e.g., `cli-starter`, `jetty-starter`, `micronaut-starter`, `spring-starter`, `starwars`).

### Step 4: Clean up

After the test run, remove the temporary repository:

```shell
rm -rf "$MLC"
```

### Putting it all together

Here is a complete single-command example that publishes and tests in one shot:

```shell
MLC="/tmp/mlc/m2-$(head -c 100 /dev/urandom | tr -dc '0-9a-z' | head -c 4)" \
  && mkdir -p "$MLC" \
  && ./gradlew clean --no-daemon \
  && ./gradlew publishToMavenLocal -PpublishMinimal -Dmaven.repo.local="$MLC" --no-daemon \
  && (cd demoapps/starwars && USE_MAVEN_LOCAL=true ./gradlew test -Dmaven.repo.local="$MLC" --no-daemon) \
  ; rm -rf "$MLC"
```

Note the `;` before `rm` — this ensures cleanup happens regardless of whether the test passed or failed.

### Notes

- The `--no-daemon` flag is recommended for isolated test runs to avoid Gradle daemon caching effects from prior runs.
- The `USE_MAVEN_LOCAL=true` environment variable causes the demoapp's `settings.gradle.kts` to add `mavenLocal()` to its repository list, which respects the `maven.repo.local` system property.
- **Always rotate the `$MLC` directory** — generate a new random path for every publish-and-test cycle.  Never reuse a previous `$MLC`, even on the same branch.  Any change to publication logic, Gradle plugin code, or dependency coordinates can leave stale artifacts that mask real failures.  The whole point of the isolated repo is to guarantee you're testing freshly-built artifacts with no contamination from prior runs.

### Debugging process-isolated worker failures

Viaduct's codegen tasks (`generateViaductGRTClassFiles`, `generateViaductJavaGRTSources`, etc.) run in process-isolated Gradle workers.  When these fail, the default output only shows:

```
> A failure occurred while executing viaduct.gradle.CodegenWorkAction
```

To see the actual root cause exception, add `--stacktrace` and grep for `Caused by`:

```shell
USE_MAVEN_LOCAL=true ./gradlew test -Dmaven.repo.local="$MLC" --no-daemon --stacktrace 2>&1 | grep "Caused by"
```

This will reveal the full causal chain, e.g.:

```
Caused by: org.gradle.workers.WorkerExecutionException: There was a failure while executing work items
Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing viaduct.gradle.CodegenWorkAction
Caused by: java.lang.reflect.InvocationTargetException
Caused by: java.lang.NoClassDefFoundError: com/github/ajalt/clikt/core/CliktCommand
Caused by: java.lang.ClassNotFoundException: com.github.ajalt.clikt.core.CliktCommand
```

The last `Caused by` line is the actual root cause.

# Demo Applications

The demo applications in this directory serve as integration tests and usage examples for the Viaduct Gradle plugins. Each demo exercises a different framework integration (Ktor, Micronaut, Jetty, Spring, plain CLI) and Kotlin version.

## KSP Test Coverage

The demo apps collectively verify the KSP registry-extractor processor across the supported Kotlin and KSP version matrix:

| Demo App | Kotlin | KSP | Generation | Coverage goal |
|---|---|---|---|---|
| cli-starter | 1.9.24 | 1.9.24-1.0.20 | KSP1 | Oldest supported |
| jetty-starter | 2.0.21 | 2.0.21-1.0.28 | KSP1 | KSP1 on 2.0 |
| micronaut-starter | 2.1.20 | 2.1.20-1.0.32 | KSP1 | KSP1 on 2.1 |
| ktor-starter | 2.1.20 | 2.1.20-2.0.1 | KSP2 | KSP2 on 2.1 (same Kotlin, different KSP) |
| starwars | 2.2.21 | 2.2.21-2.0.5 | KSP2 | Latest |

Full transition to KSP2-only will occur after we deprecate Kotlin 1.9 support. Note that Kotlin 2.3+ uses a standalone KSP model (version-independent of the compiler), which will require a separate migration when we extend support past 2.2.

For more on KSP versioning see `ksp-versioning.md`.
