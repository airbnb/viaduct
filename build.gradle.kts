import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-root")
}

orchestration {
    participatingIncludedBuilds.set(
        listOf("core", "gradle-plugins", "gradletestapps", "publications")
    )
}

val sharedFilePairs = listOf(
    "gradle.properties" to "core/gradle.properties",
    "gradle.properties" to "gradle-plugins/gradle.properties",
    "gradle.properties" to "publications/gradle.properties",
    "build-logic/common/src/main/kotlin/viaduct/gradle/shared/BuildFlags.kt" to
        "gradle-plugins/common/src/main/kotlin/viaduct/gradle/shared/BuildFlags.kt",
)

val checkSharedFileSync by tasks.registering {
    group = "verification"
    description = "Verifies that files duplicated across composite builds remain in sync."

    val inputFiles = sharedFilePairs.flatMap { (c, k) -> listOf(file(c), file(k)) }
    inputs.files(inputFiles)
    val rootDir = layout.projectDirectory

    doLast {
        val root = rootDir.asFile
        val errors = mutableListOf<String>()
        val pairList = inputFiles.chunked(2)
        for ((canonical, copy) in pairList) {
            when {
                !canonical.exists() ->
                    errors.add("Canonical file not found: ${canonical.relativeTo(root)}")
                !copy.exists() ->
                    errors.add("Copy not found: ${copy.relativeTo(root)}")
                canonical.readText() != copy.readText() ->
                    errors.add("The contents of ${canonical.relativeTo(root)} and " +
                        "${copy.relativeTo(root)} need to be identical and they are not.")
            }
        }
        if (errors.isNotEmpty()) {
            throw GradleException(errors.joinToString("\n"))
        }
    }
}

tasks.named("test") {
    dependsOn(checkSharedFileSync)
}

tasks.register("testCodeCoverageVerification") {
    group = "verification"
    description = "Verifies aggregated coverage thresholds via the core included build."
    dependsOn(gradle.includedBuild("core").task(":testCodeCoverageVerification"))
}

// The demoapps currently wired into demoappsStandaloneTest. Grow this list incrementally as each
// demoapp is verified to build against isolated, published Maven Local artifacts; see
// demoapps/AGENTS.md for the standalone-testing workflow this task automates.
val demoappsStandaloneList = listOf(
    "starwars",
    "cli-starter",
    "jetty-starter",
    "ktor-starter",
    "micronaut-starter",
    "spring-starter",
)

val demoappsStandaloneTest by tasks.registering {
    group = "verification"
    description =
        "Publishes Viaduct into a fresh, isolated Maven repository, then verifies that each " +
        "standalone demo application builds and runs against those published artifacts, using " +
        "a fresh source copy and Gradle cache per demoapp. Runs demoapps serially and fails " +
        "fast on the first failure. Not cacheable: every invocation re-publishes and re-tests " +
        "from scratch, even if nothing changed."

    // This is an end-to-end publication validation task.
    //
    // It first publishes Viaduct into a clean Maven repository, then launches each demo
    // application as a completely independent Gradle build using only those published
    // artifacts. This intentionally exercises the installation path experienced by an
    // external consumer rather than the current composite build.
    //
    // Because the task coordinates independent Gradle processes rather than modeling their
    // work as part of this build's task graph, it is not compatible with the configuration cache.
    notCompatibleWithConfigurationCache(
        "Shells out to independent per-demoapp Gradle builds; not representable as a task graph."
    )

    doLast {
        val runRoot = Files.createTempDirectory(
            Files.createDirectories(Path.of("/tmp/mlc")),
            "demoapps-standalone-"
        ).toFile()
        val mavenLocalRepo = File(runRoot, "m2").apply { mkdirs() }
        // Run-scoped --gradle-user-home + --no-build-cache so the publish step can't reuse
        // Gradle's dependency/build cache from a prior invocation or ambient developer state.
        // maven.repo.local alone only isolates published artifacts. (No --project-cache-dir here:
        // the root gradlew wrapper already injects one, and Gradle rejects a duplicate.)
        val publishGradleHome = File(runRoot, "gradle-home-publish").apply { mkdirs() }
        try {
            // clean must run as its own invocation, separate from publish: combining them in one
            // Gradle call causes race conditions where clean removes outputs needed by later
            // tasks. Without it, processResources's expand() may not restamp
            // viaduct-plugin-version.properties with the current VERSION, publishing stale
            // artifacts. See demoapps/AGENTS.md.
            exec {
                commandLine(
                    "./gradlew", "clean",
                    "--gradle-user-home", publishGradleHome.absolutePath,
                    "--no-build-cache", "--no-daemon"
                )
            }

            logger.lifecycle("Publishing Viaduct to isolated Maven local repo: $mavenLocalRepo")
            exec {
                commandLine(
                    "./gradlew", "publishToMavenLocal", "-PpublishMinimal", "-PexcludeDemoApps",
                    "-Dmaven.repo.local=${mavenLocalRepo.absolutePath}",
                    "--gradle-user-home", publishGradleHome.absolutePath,
                    "--no-build-cache", "--no-daemon"
                )
            }

            for (demoapp in demoappsStandaloneList) {
                logger.lifecycle("Testing demoapp '$demoapp' standalone against the isolated Maven local repo")

                // Copy the demoapp's git-tracked sources into runRoot instead of running in
                // place: a build/ symlink into the real demoapp directory is shared mutable
                // state that a concurrent run or an in-progress developer build can collide with.
                val demoappWorkspace = File(runRoot, demoapp)
                copyDemoappSources(demoapp, demoappWorkspace)
                val demoappGradleHome = File(runRoot, "gradle-home-$demoapp").apply { mkdirs() }
                val demoappCacheDir = File(runRoot, "cache-$demoapp").apply { mkdirs() }
                exec {
                    workingDir = demoappWorkspace
                    environment("USE_MAVEN_LOCAL", "true")
                    commandLine(
                        "./gradlew", "test",
                        "-Dmaven.repo.local=${mavenLocalRepo.absolutePath}",
                        "--gradle-user-home", demoappGradleHome.absolutePath,
                        "--project-cache-dir", demoappCacheDir.absolutePath,
                        "--no-build-cache", "--no-daemon"
                    )
                }
            }
        } catch (e: Exception) {
            logger.lifecycle("demoappsStandaloneTest failed; leaving run directory for inspection: $runRoot")
            throw e
        }
        runRoot.deleteRecursively()
    }
}

/** Copies [demoapp]'s git-tracked files into [destination], preserving executable bits. */
fun Project.copyDemoappSources(
    demoapp: String,
    destination: File,
) {
    val demoappDir = file("demoapps/$demoapp")
    val trackedFiles = ByteArrayOutputStream().use { out ->
        exec {
            workingDir = demoappDir
            commandLine("git", "ls-files", "--cached", "--others", "--exclude-standard")
            standardOutput = out
        }
        out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }
    for (relativePath in trackedFiles) {
        val source = File(demoappDir, relativePath)
        val target = File(destination, relativePath)
        target.parentFile.mkdirs()
        source.copyTo(target)
        target.setExecutable(source.canExecute())
    }
}
