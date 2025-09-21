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

includeBuild(".") // TODO - is this needed?
includeBuild("included-builds/core") {
    dependencySubstitution {
        // Engine modules
        substitute(module("com.airbnb.viaduct:engine-api")).using(project(":engine:engine-api"))
        substitute(module("com.airbnb.viaduct:engine-runtime")).using(project(":engine:engine-runtime"))

        // Service modules
        substitute(module("com.airbnb.viaduct:service-api")).using(project(":service:service-api"))
        substitute(module("com.airbnb.viaduct:service-runtime")).using(project(":service:service-runtime"))
        substitute(module("com.airbnb.viaduct:service-wiring")).using(project(":service:service-wiring"))

        // Tenant modules
        substitute(module("com.airbnb.viaduct:tenant-api")).using(project(":tenant:tenant-api"))
        substitute(module("com.airbnb.viaduct:tenant-runtime")).using(project(":tenant:tenant-runtime"))

        // Shared modules
        substitute(module("com.airbnb.viaduct:shared-arbitrary")).using(project(":shared:shared-arbitrary"))
        substitute(module("com.airbnb.viaduct:shared-dataloader")).using(project(":shared:shared-dataloader"))
        substitute(module("com.airbnb.viaduct:shared-utils")).using(project(":shared:shared-utils"))
        substitute(module("com.airbnb.viaduct:shared-logging")).using(project(":shared:shared-logging"))
        substitute(module("com.airbnb.viaduct:shared-deferred")).using(project(":shared:shared-deferred"))
        substitute(module("com.airbnb.viaduct:shared-graphql")).using(project(":shared:shared-graphql"))
        substitute(module("com.airbnb.viaduct:shared-viaductschema")).using(project(":shared:shared-viaductschema"))
        substitute(module("com.airbnb.viaduct:shared-invariants")).using(project(":shared:shared-invariants"))
        substitute(module("com.airbnb.viaduct:shared-codegen")).using(project(":shared:shared-codegen"))

        // Snipped modules
        substitute(module("com.airbnb.viaduct:snipped-errors")).using(project(":snipped:snipped-errors"))
    }
}
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
