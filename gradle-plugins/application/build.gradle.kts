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

    // Libraries the plugin source imports directly (binary schema generation).
    // tenant-codegen and serve are NOT here — they are external tool artifacts resolved at
    // build time via viaductCodegenClasspath / viaductServeClasspath Configurations.
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.viaductschema)
    // Jackson is needed for ResourceFileSchema.objectMapper() serialization at build time
    implementation(libs.jackson.module)
    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)

    // Testing (kotlin-test, junit, junit-engine, junit-launcher provided by conventions.gradle-plugin-kotlin)
    testImplementation(libs.kotest.assertions.core.jvm)
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

    val pluginIdPrefix: String by rootProject.extra

    plugins {
        create("viaductApplication") {
            id = "$pluginIdPrefix.application-gradle-plugin"
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
