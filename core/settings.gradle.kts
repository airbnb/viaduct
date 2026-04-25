pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("../build-logic")
}

plugins {
    id("settings.common")
}

// Standalone `./gradlew -p core ...` needs core's own subprojects to participate in composite
// auto-substitution. Without self-inclusion, `version = INCLUDED` module deps resolve externally.
includeBuild(".")
includeBuild("../build-logic")

// Include core modules
include(":engine:api")
include(":engine:runtime")
include(":engine:wiring")
include(":service")
include(":service:api")
include(":service:runtime")
include(":service:wiring")
include(":tenant:api")
include(":tenant:codegen")
include(":tenant:ksp")
include(":tenant:validation")
include(":tenant:runtime")
include(":tenant:tutorials")
include(":tenant:wiring")

// Include Java API modules (skipped when publishMinimal is set — not needed by demoapps)
if (!providers.gradleProperty("publishMinimal").isPresent) {
    include(":x:javaapi:api")
    include(":x:javaapi:codegen")
    include(":x:javaapi:runtime")
}

// Include all shared modules
include(":shared:apiannotations")
include(":shared:arbitrary")
include(":shared:dataloader")
include(":shared:utils")
include(":shared:deferred")
include(":shared:graphql")
include(":shared:viaductschema")
include(":shared:invariants")
include(":shared:codegen")
include(":shared:mapping")
include(":shared:errors")
include(":snipped:errors")

// Serve module (development server runtime)
include(":service:serve")
