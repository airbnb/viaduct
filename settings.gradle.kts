import viaduct.gradle.internal.includeNamed

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

// Gradle auto-substitution (matching group:name) only applies to subprojects of proper included
// builds (IncludedBuildState). ROOT subprojects (RootBuildState) are NOT registered in the
// composite substitution table, so the three rules below are required for the included demoapp
// builds to resolve com.airbnb.viaduct:api/runtime/test-fixtures to their local counterparts.
includeBuild(".") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct:api")).using(project(":api"))
        substitute(module("com.airbnb.viaduct:buildtime")).using(project(":buildtime"))
        substitute(module("com.airbnb.viaduct:runtime")).using(project(":runtime"))
        substitute(module("com.airbnb.viaduct:test-fixtures")).using(project(":test-fixtures"))
    }
}

// All core subprojects publish under names matching their Gradle project names,
// so auto-substitution handles them without any explicit rules.
includeBuild("core")

// All gradle-plugins subprojects publish under names matching their Gradle project names,
// so auto-substitution handles them without any explicit rules.
includeBuild("gradle-plugins")

// demo apps
includeBuild("demoapps/cli-starter")
includeBuild("demoapps/jetty-starter")
includeBuild("demoapps/ktor-starter")
includeBuild("demoapps/micronaut-starter")
includeBuild("demoapps/starwars")

// misc
include(":docs")
includeNamed(":viaduct-bom", projectName = "bom")
include(":api")
include(":buildtime")
include(":runtime")
include(":test-fixtures")
