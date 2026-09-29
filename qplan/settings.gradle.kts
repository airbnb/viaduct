pluginManagement {
    repositories {
        val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        } else {
            gradlePluginPortal()
        }
    }
    includeBuild("../build-logic")

    plugins {
        id("me.champeau.jmh") version "0.7.3"
    }
}

plugins {
    id("settings.common")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            library("jakarta-inject", "jakarta.inject", "jakarta.inject-api").version("2.0.1")
        }
    }
}

rootProject.name = "qplanning"

includeBuild("../build-logic")
includeBuild("../core")
includeBuild("spec") {
    name = "graphql-spec"
}

include("arbitrary", "execution", "model", "semantics")
