import viaduct.gradle.internal.includeNamed

pluginManagement {
    includeBuild("build-logic")
}

plugins {
    id("settings.common")
    id("settings.build-scans")
}

rootProject.name = "viaduct"

// Gradle auto-substitution (matching group:name) only applies to subprojects of proper included
// builds (IncludedBuildState). ROOT subprojects (RootBuildState) are NOT registered in the
// composite substitution table, so the three rules below are required for the included demoapp
// builds to resolve com.airbnb.viaduct:api/runtime/test-fixtures to their local counterparts.
includeBuild(".") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct:api")).using(project(":api"))
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
include(":runtime")
include(":test-fixtures")
