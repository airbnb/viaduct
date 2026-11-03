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
    id("com.airbnb.viaduct.kotlinapi") apply false
    id("com.airbnb.viaduct.javaapi") apply false
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
    usePlugin("com.airbnb.viaduct.kotlinapi")
    project(":one-project")
    modulePackagePrefix("com.example.execution.oneproject")

    includeModule {
        project(":one-project")
        modulePackageSuffix("resolvers")
    }
}

includeViaductApplication {
    usePlugin("com.airbnb.viaduct.javaapi")
    usePlugin("com.airbnb.viaduct.kotlinapi")
    project(":java-one-project")
    modulePackagePrefix("com.example.execution.javaoneproject")

    includeModule {
        project(":java-one-project")
        modulePackageSuffix("resolvers")
    }
}

includeViaductApplication {
    usePlugin("com.airbnb.viaduct.kotlinapi")
    usePlugin("com.airbnb.viaduct.javaapi")
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
    usePlugin("com.airbnb.viaduct.kotlinapi")
    project(":two-project")
    modulePackagePrefix("com.example.execution.twoproject")

    includeModule {
        project(":two-project:resolvers")
        modulePackageSuffix("resolvers")
    }
}
