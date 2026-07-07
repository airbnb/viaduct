// Self-contained composite build for the remote-resolvers demo. Run the servers from THIS
// directory, e.g. `./gradlew :main-server:run` — everything (Viaduct, the remote-resolvers lib,
// and the StarWars tenant modules) is built from source via the included builds below, so no
// publish step is required and nothing here is published remotely.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("gradle/viaduct.versions.toml"))
            // com.airbnb.viaduct:* is source-substituted from the included builds below, so this
            // version is only a placeholder to satisfy the catalog ref — never used for resolution.
            version("viaduct", "0.0.0")
        }
    }
}

rootProject.name = "remoteresolvers-demo"

// Viaduct engine + published-artifact facade, from source (no Maven publish needed to run).
includeBuild("../../../core")
includeBuild("../../../publications")
includeBuild("../../../gradle-plugins")

// The remote-resolvers library (consumed by the servers as com.airbnb.viaduct:remoteresolvers).
includeBuild("lib")

// The StarWars tenant modules (the resolvers the demo runs), from source. They are not published,
// so substitute their Maven coordinates onto the demoapp's project outputs.
includeBuild("../../../demoapps/starwars") {
    dependencySubstitution {
        substitute(module("com.example.starwars:common")).using(project(":common"))
        substitute(module("com.example.starwars:filmography")).using(project(":modules:filmography"))
        substitute(module("com.example.starwars:universe")).using(project(":modules:universe"))
    }
}

// The two demo servers, as subprojects, so `./gradlew :main-server:run` / `:remote-server:run`
// work directly from this directory.
include(":main-server")
project(":main-server").projectDir = file("starwars/main-server")
include(":remote-server")
project(":remote-server").projectDir = file("starwars/remote-server")
