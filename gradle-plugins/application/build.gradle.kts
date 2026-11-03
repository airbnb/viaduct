plugins {
    `kotlin-dsl`
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("com.gradle.plugin-publish") version "2.0.0"
    id("conventions.viaduct-publishing")
    id("conventions.viaduct-fat-plugin")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api(project(":conventions"))

    // Testing (kotlin-test, junit, junit-engine, junit-launcher provided by conventions.gradle-plugin-kotlin)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.viaduct.shared.bootstrap)
    testImplementation(gradleTestKit())
    testImplementation(project(":settings"))
}

// Include version in JAR manifest for JAR introspection and debugging
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
        create("viaductApplication") {
            id = "${libs.versions.pluginIdPrefix.get()}.application-gradle-plugin"
            implementationClass = "viaduct.gradle.ViaductApplicationPlugin"
            displayName = "Viaduct :: Application Plugin"
            description = "Application plugin for Viaduct-based apps."
            tags.set(listOf("viaduct", "graphql", "kotlin"))
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("viaduct-plugin-version.properties") {
        expand("version" to pluginVersion)
    }
}

viaductPublishing {
    name.set("Application Gradle Plugin")
    description.set("Gradle plugin for Viaduct application projects.")
}
