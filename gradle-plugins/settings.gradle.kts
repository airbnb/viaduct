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

// Standalone `./gradlew -p gradle-plugins ...` needs composite substitution for dependencies
// that normally resolve because OSS root includes both `core` and `build-logic`.
includeBuild("../core")
includeBuild("../build-logic")

includeNamed(":common", projectName = "plugins-common")
includeNamed(":application", projectName = "plugins-application")
includeNamed(":module", projectName = "plugins-module")
includeNamed(":module-java", projectName = "plugins-module-java")

gradle.allprojects {
    extra["pluginIdPrefix"] = "com.airbnb.viaduct"
}
