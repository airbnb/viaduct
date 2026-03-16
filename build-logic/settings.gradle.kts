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

include(":common")
include(":shared")
include(":test-support")

// build-logic's .gradle/ and build/ dirs are redirected to dist/ via symlinks
// created by gradlew. layout.buildDirectory can't be used for build-logic
// because Dokka's precompiled script plugin accessor generation breaks with
// non-default build dirs.
