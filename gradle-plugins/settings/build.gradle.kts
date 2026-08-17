plugins {
    `kotlin-dsl`
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("com.gradle.plugin-publish") version "2.0.0"
    id("conventions.viaduct-publishing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":common"))

    testImplementation(gradleTestKit())
    testImplementation(libs.kotest.assertions.core.jvm)
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString()
        )
    }
}

gradlePlugin {
    website = "https://viaduct.airbnb.tech"
    vcsUrl = "https://github.com/airbnb/viaduct"

    plugins {
        create("viaductSettings") {
            id = "${libs.versions.pluginIdPrefix.get()}.settings-gradle-plugin"
            implementationClass = "viaduct.gradle.ViaductSettingsPlugin"
            displayName = "Viaduct :: Settings Plugin"
            description = "Settings plugin for declaring Viaduct application topology."
            tags.set(listOf("viaduct", "graphql", "settings"))
        }
    }
}

viaductPublishing {
    name.set("Settings Gradle Plugin")
    description.set("Gradle settings plugin for declaring Viaduct application topology.")
}
