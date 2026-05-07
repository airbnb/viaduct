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

// experimental — remoteresolvers proxy library and rrp-server consumer.
includeBuild("x/remoteresolvers")
includeBuild("x/remoteresolvers/rrp-server")

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

include(":docs")
