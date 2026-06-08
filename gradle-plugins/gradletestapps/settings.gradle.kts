pluginManagement {
    includeBuild("../../build-logic")
    includeBuild("..")
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("settings.common")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "gradletestapps"

include(":one-project")
include(":multi-project")
include(":multi-project:alpha")
include(":multi-project:beta")
include(":two-project")
include(":two-project:resolvers")
