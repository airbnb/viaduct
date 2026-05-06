package settings

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

pluginManagement {
    plugins {
        id("org.jetbrains.dokka")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
    ?.takeIf { url ->
        try {
            InetAddress.getByName(URI(url).host)
            true
        } catch (_: UnknownHostException) {
            false
        }
    }

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        } else {
            mavenCentral()
        }
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
}

// If viaduct.distDir is set (via -D from gradlew), redirect build/ dirs there
// so all Gradle outputs live under a single directory that file watchers can
// ignore. The .gradle/ dirs and build-logic/build are handled by symlinks
// created in gradlew before Gradle starts.
val distDir = providers.systemProperty("viaduct.distDir").orNull

gradle.allprojects {
    group = "com.airbnb.viaduct"
    if (distDir != null) {
        val dist = file(distDir).resolve(settings.rootProject.name)
        layout.buildDirectory = dist.resolve("build/${project.path.replace(":", "/")}")
    }
}
