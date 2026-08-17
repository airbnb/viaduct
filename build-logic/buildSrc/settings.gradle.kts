pluginManagement {
    repositories {
        val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        } else {
            gradlePluginPortal()
        }
    }
}

val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        } else {
            gradlePluginPortal()
        }
    }
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS

    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}
