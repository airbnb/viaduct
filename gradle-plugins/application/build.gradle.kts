plugins {
    `kotlin-dsl`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("com.gradle.plugin-publish") version "2.0.0"
    id("conventions.viaduct-publishing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":plugins-common"))

    // Libraries the plugin source imports directly (binary schema generation, scaffold templates).
    // tenant-codegen and serve are NOT here — they are external tool artifacts resolved at
    // build time via viaductCodegenClasspath / viaductServeClasspath Configurations.
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.viaductschema)
    implementation(libs.viaduct.shared.codegen) // StringTemplate utilities for scaffolding

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions.core.jvm)
    testRuntimeOnly(libs.junit.engine)
    testImplementation(gradleTestKit())
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
            // e.g., com.airbnb.viaduct.application-gradle-plugin
            id = "$group.application-gradle-plugin"
            implementationClass = "viaduct.gradle.ViaductApplicationPlugin"
            displayName = "Viaduct :: Application Plugin"
            description = "Application plugin for Viaduct-based apps."
            tags.set(listOf("viaduct", "graphql", "kotlin"))
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    filesMatching("viaduct-plugin-version.properties") {
        expand("version" to project.version)
    }
}

viaductPublishing {
    name.set("Application Gradle Plugin")
    description.set("Gradle plugin for Viaduct application projects.")
}
