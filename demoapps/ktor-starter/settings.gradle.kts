rootProject.name = "viaduct-ktor-starter"

val viaductVersion: String by settings

// When part of composite build, use local gradle-plugins and shaded jar
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

// Use local Viaduct modules and BOM when part of composite build
if (gradle.parent != null) {
    includeBuild("../../included-builds/core")
    includeBuild("../..") {
        dependencySubstitution {
            substitute(module("com.airbnb.viaduct:viaduct-bom")).using(project(":viaduct-bom"))
        }
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()  // For local development
    }
    versionCatalogs {
        create("libs") {
            // This injects a dynamic value that your TOML can reference.
            version("viaduct", viaductVersion)
        }
    }
}

include(":resolvers")
