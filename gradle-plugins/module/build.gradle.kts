plugins {
    `kotlin-dsl`
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
    id("com.gradle.plugin-publish") version "2.0.0"
    id("conventions.viaduct-publishing")
    id("conventions.viaduct-fat-plugin")
}

java {
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(project(":common"))

    // Libraries the plugin source imports directly (binary schema generation).
    // tenant-codegen is NOT here — it is an external tool artifact resolved at
    // build time via the viaductCodegenClasspath Configuration.
    // Classes bundled directly into this plugin JAR via conventions.viaduct-fat-plugin.
    bundled(libs.viaduct.shared.graphql)
    bundled(libs.viaduct.shared.viaductschema)

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)

    // Testing
    testImplementation(gradleTestKit())
    testImplementation(project(":application"))
    testImplementation(project(":settings"))
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.ksp.gradle.plugin)
    testImplementation(libs.kotlin.gradle.plugin)
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
        create("viaductModule") {
            id = "$pluginIdPrefix.module-gradle-plugin"
            implementationClass = "viaduct.gradle.ViaductModulePlugin"
            displayName = "Viaduct :: Module Plugin"
            description = "Module plugin for Viaduct tenant modules."
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
    name.set("Module Gradle Plugin")
    description.set("Gradle plugin for Viaduct tenant modules.")
}
