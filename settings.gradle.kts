import viaduct.gradle.internal.includeNamed

pluginManagement {
    includeBuild("build-logic")
    includeBuild("gradle-plugins")
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

// All included-builds/core subprojects publish under names matching their Gradle project names,
// so auto-substitution handles them without any explicit rules.
includeBuild("included-builds/core")
// The gradle-plugins projects publish under artifact IDs that differ from their Gradle project
// names (e.g. ":application-plugin" publishes as "application-gradle-plugin"), so explicit
// substitution is required — auto-substitution can't match them.
includeBuild("gradle-plugins") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct:gradle-plugins-common")).using(project(":common"))
        substitute(module("com.airbnb.viaduct:module-gradle-plugin")).using(project(":module-plugin"))
        substitute(module("com.airbnb.viaduct:application-gradle-plugin")).using(project(":application-plugin"))
    }
}

// demo apps
includeBuild("demoapps/cli-starter")
includeBuild("demoapps/jetty-starter")
includeBuild("demoapps/ktor-starter")
includeBuild("demoapps/micronaut-starter")
includeBuild("demoapps/starwars")

include(":tenant:tutorials")
include(":x:javaapi:runtime-integration-tests")

// misc
include(":docs")
includeNamed(":viaduct-bom", projectName = "bom")
include(":api")
include(":runtime")
include(":test-fixtures")
includeBuild("build-logic") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct.build:common")).using(project(":common"))
    }
}
