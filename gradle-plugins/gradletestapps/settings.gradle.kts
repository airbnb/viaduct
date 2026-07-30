pluginManagement {
    includeBuild("../../build-logic")
    includeBuild("..")
    repositories {
        val artifactoryMirror = System.getenv("VIADUCT_ARTIFACTORY_MIRROR")
        if (artifactoryMirror != null) {
            maven { url = uri(artifactoryMirror) }
        } else {
            gradlePluginPortal()
        }
    }
}

plugins {
    id("settings.common")
    id("com.airbnb.viaduct.settings-gradle-plugin")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "gradletestapps"

includeViaductApplication {
    project(":one-project")
    modulePackagePrefix("com.example.execution.oneproject")

    includeModule {
        project(":one-project")
        modulePackageSuffix("resolvers")
    }
}

includeViaductApplication {
    project(":multi-project")
    modulePackagePrefix("com.example.execution.multiproject")

    includeModule {
        project(":multi-project:alpha")
        modulePackageSuffix("alpha")
    }
    includeModule {
        project(":multi-project:beta")
        modulePackageSuffix("beta")
    }
}

includeViaductApplication {
    project(":two-project")
    modulePackagePrefix("com.example.execution.twoproject")

    includeModule {
        project(":two-project:resolvers")
        modulePackageSuffix("resolvers")
    }
}
