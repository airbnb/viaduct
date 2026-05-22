@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
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
