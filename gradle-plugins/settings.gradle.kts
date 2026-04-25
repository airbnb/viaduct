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

// Standalone `./gradlew -p gradle-plugins ...` needs composite substitution for dependencies
// that normally resolve because OSS root includes both `core` and `build-logic`.
includeBuild("../core")
includeBuild("../build-logic")

include(":common")
include(":application")
include(":module")

gradle.allprojects {
    group = "com.airbnb.viaduct.gradle"
    extra["pluginIdPrefix"] = "com.airbnb.viaduct"
}
