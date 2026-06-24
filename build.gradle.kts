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

// remoteresolvers is a single-module included build, so the orchestration's
// subproject-fanout aggregates don't reach it. Wire its publishToMavenLocal
// directly so main-server (a sibling included build) can resolve the artifact.
tasks.named("publishToMavenLocal") {
    dependsOn(gradle.includedBuild("remoteresolvers").task(":publishToMavenLocal"))
}

// remoteresolvers is a single-module included build, so the orchestration's subproject-fanout
// aggregates don't reach it. Wire its verification lifecycle into the root so CI's `check`
// compiles and tests it, enforcing the Kotlin -Werror flip matching the Bazel build's -Werror
listOf("check", "build", "test").forEach { lifecycle ->
    tasks.named(lifecycle) {
        dependsOn(gradle.includedBuild("remoteresolvers").task(":$lifecycle"))
    }
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
