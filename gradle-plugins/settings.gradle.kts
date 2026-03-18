import viaduct.gradle.internal.includeNamed

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("../build-logic")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots")
        }
    }
}

plugins {
    id("settings.common")
}

includeNamed(":common", projectName = "plugins-common")
includeNamed(":application", projectName = "plugins-application")
includeNamed(":module", projectName = "plugins-module")
