import viaduct.gradle.internal.includeNamed

pluginManagement {
    includeBuild("build-logic")
    includeBuild("build-test-plugins")
    includeBuild("gradle-plugins")
}

plugins {
    id("settings.common")
    id("settings.build-scans")
}

rootProject.name = "viaduct"

includeBuild(".")
includeBuild("included-builds/core")
includeBuild("gradle-plugins") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct:gradle-plugins-common")).using(project(":common"))
        substitute(module("com.airbnb.viaduct:module-gradle-plugin")).using(project(":module-plugin"))
        substitute(module("com.airbnb.viaduct:application-gradle-plugin")).using(project(":application-plugin"))
    }
}

// demo apps
includeBuild("demoapps/cli-starter")
includeBuild("demoapps/ktor-starter")
includeBuild("demoapps/starwars")
includeBuild("demoapps/spring-starter")

// integration tests
include(":tenant:codegen-integration-tests")
include(":tenant:api-integration-tests")
include(":tenant:runtime-integration-tests")
include(":tenant:tutorials")

// misc
include(":docs")
includeNamed(":viaduct-bom", projectName = "bom")
include(":tools")
