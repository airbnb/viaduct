import viaduct.gradle.internal.includeNamed

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("../../build-logic")
}

plugins {
    id("settings.common")
}

// Include core modules
includeNamed(":engine:api", "../..")
includeNamed(":engine:runtime", "../..")
includeNamed(":engine:wiring", "../..")
includeNamed(":service", "../..")
includeNamed(":service:api", "../..")
includeNamed(":service:runtime", "../..")
includeNamed(":service:wiring", "../..")
includeNamed(":tenant:api", "../..")
includeNamed(":tenant:codegen", "../..")
includeNamed(":tenant:runtime", "../..")
includeNamed(":tenant:wiring", "../..")

// Include Java API modules (skipped when publishMinimal is set — not needed by demoapps)
if (!providers.gradleProperty("publishMinimal").isPresent) {
    includeNamed(":x:javaapi:api", "../..")
    includeNamed(":x:javaapi:codegen", "../..")
    includeNamed(":x:javaapi:runtime", "../..")
}

// Include all shared modules
includeNamed(":shared:apiannotations", "../..")
includeNamed(":shared:arbitrary", "../..")
includeNamed(":shared:dataloader", "../..")
includeNamed(":shared:utils", "../..")
includeNamed(":shared:deferred", "../..")
includeNamed(":shared:graphql", "../..")
includeNamed(":shared:viaductschema", "../..")
includeNamed(":shared:invariants", "../..")
includeNamed(":shared:codegen", "../..")
includeNamed(":shared:mapping", "../..")
includeNamed(":errors", "../..")
includeNamed(":snipped:errors", "../..")

// Serve module (development server runtime)
includeNamed(":serve", "../..")
