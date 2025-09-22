import viaduct.gradle.internal.includeNamed

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("build-logic")
    includeBuild("build-test-plugins")
}

plugins {
    id("settings.common")
    id("settings.build-scans")
}

rootProject.name = "viaduct"

includeBuild(".")
includeBuild("included-builds/core")
includeBuild("included-builds/codegen")
includeBuild("gradle-plugins") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct:gradle-plugins-common")).using(project(":common"))
        substitute(module("com.airbnb.viaduct:module-gradle-plugin")).using(project(":module-plugin"))
        substitute(module("com.airbnb.viaduct:application-gradle-plugin")).using(project(":application-plugin"))
    }
}

// demo apps
includeBuild("demoapps/cli-starter")
includeBuild("demoapps/starwars")
includeBuild("demoapps/spring-starter")

// integration tests
include(":tenant:codegen-integration-tests")
include(":tenant:api-integration-tests")
include(":tenant:runtime-integration-tests")

// testapps
include("tenant:testapps:fixtures")

/*
include("tenant:testapps:policycheck")
include("tenant:testapps:policycheck:tenants:tenant1")
include("tenant:testapps:policycheck:schema")
include("tenant:testapps:resolver")
include("tenant:testapps:resolver:tenants:tenant1")
include("tenant:testapps:resolver:tenants:tenant2")
include("tenant:testapps:resolver:tenants:tenant3")
include("tenant:testapps:resolver:schema")
include("tenant:testapps:schemaregistration")
include("tenant:testapps:schemaregistration:tenants:tenant1")
include("tenant:testapps:schemaregistration:tenants:tenant2")
include("tenant:testapps:schemaregistration:schema")
*/

// misc
include(":docs")
includeNamed(":viaduct-bom", projectName = "bom")
include(":tools")
