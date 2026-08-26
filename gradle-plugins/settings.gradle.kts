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
}

val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        } else {
            mavenCentral()
            gradlePluginPortal()
            maven {
                url = uri("https://central.sonatype.com/repository/maven-snapshots")
            }
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

plugins {
    id("settings.common")
}

// Standalone `./gradlew -p gradle-plugins ...` needs composite substitution for dependencies
// that normally resolve because OSS root includes `core`, `build-logic`, and `publications`.
includeBuild("../core")
includeBuild("../build-logic")
includeBuild("../publications")

include(":common")
include(":settings")
include(":application")
include(":module")
include(":module-java")

gradle.allprojects {
    group = "com.airbnb.viaduct.gradle"
}
