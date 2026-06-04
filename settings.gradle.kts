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

// The publish step (publishToMavenLocal) only needs the published builds: core,
// publications, gradle-plugins (above) and the remoteresolvers proxy library (below).
// The experimental rrp/rrs servers and the demo apps are passive composite participants;
// configuring them resolves their third-party Gradle plugins from Maven Central, which gets
// rate-limited (429) during CI's parallel publish. -PexcludeDemoApps skips them. (rrp/rrs
// depend on com.example.starwars, so they are skipped together with the demo apps.)
val excludeDemoApps = providers.gradleProperty("excludeDemoApps").isPresent

// experimental — remoteresolvers proxy library (published; consumed by the rrp/rrs servers)
includeBuild("x/remoteresolvers")

if (!excludeDemoApps) {
    // rrp-server (engine side) and rrs-server (resolver side, for NETWORK transport)
    includeBuild("x/remoteresolvers/rrp-server")
    includeBuild("x/remoteresolvers/rrs-server")

    // demo apps
    includeBuild("demoapps/cli-starter")
    includeBuild("demoapps/jetty-starter")
    includeBuild("demoapps/ktor-starter")
    includeBuild("demoapps/micronaut-starter")
    includeBuild("demoapps/spring-starter")
    includeBuild("demoapps/starwars") {
        dependencySubstitution {
            // Expose StarWars module outputs via Maven coordinates so other included
            // builds in the composite (e.g. rrp-server) can resolve them.
            substitute(module("com.example.starwars:common")).using(project(":common"))
            substitute(module("com.example.starwars:filmography")).using(project(":modules:filmography"))
            substitute(module("com.example.starwars:universe")).using(project(":modules:universe"))
        }
    }
}

include(":docs")
