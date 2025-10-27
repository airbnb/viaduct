// tag::dependency-resolution[30] How to setup settings.gradle.kts on a new viaduct application

val viaductVersion: String by settings

// When part of composite build, use local gradle-plugins
// When standalone, use Maven Central (only after version is published)
pluginManagement {
    if (gradle.parent != null) {
        includeBuild("../../gradle-plugins")
    } else {
        repositories {
            mavenCentral()
            gradlePluginPortal()
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            // This injects a dynamic value that your TOML can reference.
            version("viaduct", viaductVersion)
        }
    }
}
