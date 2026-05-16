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

// Standalone builds need composite substitution for core module dependencies.
includeBuild("../core")
includeBuild("../build-logic")

include(":api")
include(":runtime")
include(":buildtime")
include(":test-fixtures")
include(":bom")
include(":javaapi-api")
include(":javaapi-runtime")
include(":javaapi-buildtime")
