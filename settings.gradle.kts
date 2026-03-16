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

includeBuild(".") {
    dependencySubstitution {
        // Bundle modules for simplified dependency management
        substitute(module("com.airbnb.viaduct:api")).using(project(":api"))
        substitute(module("com.airbnb.viaduct:runtime")).using(project(":runtime"))
        substitute(module("com.airbnb.viaduct:test-fixtures")).using(project(":test-fixtures"))
    }
}
includeBuild("included-builds/core")
includeBuild("gradle-plugins") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct:gradle-plugins-common")).using(project(":common"))
        substitute(module("com.airbnb.viaduct:module-gradle-plugin")).using(project(":module-plugin"))
        substitute(module("com.airbnb.viaduct:application-gradle-plugin")).using(project(":application-plugin"))
        substitute(module("com.airbnb.viaduct:serve")).using(project(":core:serve"))
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
