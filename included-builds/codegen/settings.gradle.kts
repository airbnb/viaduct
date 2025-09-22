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

// Include only codegen module
includeNamed(":tenant:codegen", "../..")

