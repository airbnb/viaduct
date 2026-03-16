import viaduct.gradle.internal.includeNamed

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
    includeBuild("../build-logic")
}

includeBuild("../build-logic") {
    dependencySubstitution {
        substitute(module("com.airbnb.viaduct.build:shared")).using(project(":shared"))
    }
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

includeNamed(":common")
includeNamed(":application-plugin")
includeNamed(":module-plugin")
