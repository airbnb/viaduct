pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("../build-logic")
}

plugins {
    id("settings.common")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// Standalone `./gradlew -p core ...` needs core's own subprojects to participate in composite
// auto-substitution. Without self-inclusion, `version = INCLUDED` module deps resolve externally.
includeBuild(".")
includeBuild("../build-logic")
// Core modules depend on the experimental x/remoteresolvers module; include it here so standalone
// core builds (e.g. the OSV Gradle CI job) can substitute its coordinate too.
includeBuild("../x/remoteresolvers")

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

// Java API modules
include(":x:javaapi:api")
include(":x:javaapi:codegen")
include(":x:javaapi:runtime")

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

// Serve module (development server runtime)
include(":service:serve")

// Override the default group (com.airbnb.viaduct from settings.common) with path-based
// subgroups. This must happen at settings time so that composite build auto-substitution
// registers the correct group:name coordinates for each project.
val subgroupRoots = setOf("engine", "service", "tenant", "shared")

gradle.allprojects {
    val segments = path.split(":").filter { it.isNotEmpty() }
    group = when {
        segments.size >= 2 && segments.first() == "x" ->
            "com.airbnb.viaduct.${segments[1]}"
        segments.firstOrNull() in subgroupRoots ->
            "com.airbnb.viaduct.${segments.first()}"
        else -> "com.airbnb.viaduct"
    }
}
