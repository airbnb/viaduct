rootProject.name = "viaduct-micronaut-starter"

val viaductVersion: String by settings

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
            version("viaduct", viaductVersion)
        }
    }
}

include(":resolvers")
