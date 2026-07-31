// When part of composite build, use local gradle-plugins
// When standalone, use Maven Central (only after version is published)
pluginManagement {
    val viaductVersion: String by settings

    if (gradle.parent != null) {
        includeBuild("../../gradle-plugins")
    } else {
        repositories {
            if (System.getenv("USE_MAVEN_LOCAL")?.toBoolean() == true) mavenLocal()
            if (System.getenv("USE_VIADUCT_SNAPSHOT_REPO")?.toBoolean() == true) {
                maven("https://central.sonatype.com/repository/maven-snapshots/")
            }
            val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
            if (artifactoryMirror != null) {
                maven { url = uri(artifactoryMirror) }
            } else {
                gradlePluginPortal()
            }
        }
    }
    plugins {
        id("com.airbnb.viaduct.settings-gradle-plugin") version viaductVersion
    }
}

plugins {
    id("com.airbnb.viaduct.settings-gradle-plugin")
}

rootProject.name = "viaduct-micronaut-starter"

val viaductVersion: String by settings

dependencyResolutionManagement {
    repositories {
        if (System.getenv("USE_MAVEN_LOCAL")?.toBoolean() == true) mavenLocal()
        if (System.getenv("USE_VIADUCT_SNAPSHOT_REPO")?.toBoolean() == true) {
            maven("https://central.sonatype.com/repository/maven-snapshots/")
        }
        val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        } else {
            mavenCentral()
        }
    }
    versionCatalogs {
        create("libs") {
            // This injects a dynamic value that your TOML can reference.
            version("viaduct", viaductVersion)
        }
    }
}

include(":common")

includeViaductApplication {
    project(":")
    modulePackagePrefix("com.example")

    includeModule {
        project(":viadapp")
        modulePackageSuffix("viadapp")
    }
}
