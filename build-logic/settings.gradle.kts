// Route build-logic's own plugin + dependency resolution through the Artifactory mirror when CI sets
// VIADUCT_ARTIFACTORY_MIRROR (and its host resolves); otherwise fall back to the Gradle Plugin Portal.
// build-logic provides settings.common, so it can't use that plugin to set its own repositories
// (chicken-and-egg) — without this its bootstrap (kotlin-dsl / kotlin-gradle-plugin / kotlin-stdlib)
// resolves from Maven Central and is rate-limited (429) under CI load. Fully-qualified java.net names
// are used because settings-script imports don't apply inside pluginManagement, which Gradle compiles
// first; the lookup likewise can't be shared via a val declared after it. Mirrors common.settings.gradle.kts.
pluginManagement {
    repositories {
        System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
            ?.takeIf { runCatching { java.net.InetAddress.getByName(java.net.URI(it).host) }.isSuccess }
            ?.let { maven { url = uri(it) } }
        gradlePluginPortal()
    }
}

val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
    ?.takeIf { runCatching { java.net.InetAddress.getByName(java.net.URI(it).host) }.isSuccess }

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        }
        gradlePluginPortal()
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":build-common")
project(":build-common").projectDir = file("common")
include(":build-test-support")
project(":build-test-support").projectDir = file("gradle-only/test-support")
include(":build-ktlint")
project(":build-ktlint").projectDir = file("gradle-only/ktlint")

// build-logic's .gradle/ and build/ dirs are redirected to dist/ via symlinks
// created by gradlew. layout.buildDirectory can't be used for build-logic
// because Dokka's precompiled script plugin accessor generation breaks with
// non-default build dirs.
