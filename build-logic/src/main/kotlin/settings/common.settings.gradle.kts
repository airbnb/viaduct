package settings

pluginManagement {
    plugins {
        id("org.jetbrains.dokka")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
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
