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
