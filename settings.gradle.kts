pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("settings.common")
    id("settings.build-scans")
}

rootProject.name = "viaduct"

// Verify that the KSP version in the version catalog is aligned with the Kotlin version.
// KSP versions are formatted as "<kotlin-version>-<ksp-release>", so the KSP version
// string must start with the Kotlin version string.
run {
    val lines = file("gradle/libs.versions.toml").readLines()
    fun versionOf(key: String): String? =
        lines.firstOrNull { it.trimStart().startsWith("$key ") || it.trimStart().startsWith("$key=") }
            ?.substringAfter("=")?.trim()?.removeSurrounding("\"")
            ?.substringBefore("#")?.trim()

    val kotlin = versionOf("kotlin")
    val ksp = versionOf("ksp")
    require(lines.none { it.contains("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm") }) {
        "Use org.jetbrains.kotlinx:kotlinx-coroutines-core in gradle/libs.versions.toml; " +
            "kotlinx-coroutines-core-jvm is redundant."
    }

    if (kotlin != null && ksp != null) {
        require(ksp.startsWith("$kotlin-")) {
            "KSP version ($ksp) must start with the Kotlin version ($kotlin-). " +
                "Update the ksp version in gradle/libs.versions.toml."
        }
    }
}

// Included builds participate in composite auto-substitution:
// Gradle matches group:name of external dependencies to included build projects.
includeBuild("core")
includeBuild("publications")
includeBuild("gradle-plugins")
includeBuild("gradle-plugins/gradletestapps")

// The publish step (publishToMavenLocal) only needs the published builds: core,
// publications, and gradle-plugins (above).
// The demo apps are passive composite participants; configuring them resolves their third-party
// Gradle plugins from Maven Central, which gets rate-limited (429) during CI's parallel publish.
// -PexcludeDemoApps skips them.
val excludeDemoApps = providers.gradleProperty("excludeDemoApps").isPresent

// The experimental remoteresolvers lib is a non-participating included build: built from source and
// static-analyzed in CI (see _infra/ci/jobs/static_analysis.yml), but never published to Maven
// Central (it is not in orchestration.participatingIncludedBuilds); its tests run via Bazel. Its
// StarWars demo servers live in a separate self-contained composite at core/x/remoteresolvers (built
// and run from there — see its README) and are intentionally NOT part of this composite.
includeBuild("core/x/remoteresolvers/lib") { name = "remoteresolvers" }

if (!excludeDemoApps) {

    // demo apps
    includeBuild("demoapps/cli-starter")
    includeBuild("demoapps/jetty-starter")
    includeBuild("demoapps/ktor-starter")
    includeBuild("demoapps/micronaut-starter")
    includeBuild("demoapps/spring-starter")
    includeBuild("demoapps/starwars") {
        dependencySubstitution {
            // Expose StarWars module outputs via Maven coordinates so other included
            // builds in the composite (e.g. main-server) can resolve them.
            substitute(module("com.example.starwars:common")).using(project(":common"))
            substitute(module("com.example.starwars:filmography")).using(project(":modules:filmography"))
            substitute(module("com.example.starwars:universe")).using(project(":modules:universe"))
        }
    }
}

include(":docs")
