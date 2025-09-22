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
    implementation(project(":common"))

    // Your runtime helpers used by the plugin implementation (keep as needed)
    implementation("com.airbnb.viaduct:tenant-codegen")
    implementation("com.airbnb.viaduct:shared-graphql")

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
}

// Manifest with Implementation-Version for runtime access if you need it
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString()
        )
    }
}

gradlePlugin {
    website = "https://airbnb.io/viaduct"
    vcsUrl = "https://github.com/airbnb/viaduct"

    plugins {
        create("viaductModule") {
            id = "$group.module-gradle-plugin"
            implementationClass = "viaduct.gradle.ViaductModulePlugin"
            displayName = "Viaduct :: Module Plugin"
            description = "Module plugin for Viaduct tenant modules."
            tags.set(listOf("viaduct", "graphql", "kotlin"))
        }
    }
}

viaductPublishing {
    name.set("Module Gradle Plugin")
    description.set("Gradle plugin for Viaduct tenant modules.")
    artifactId.set("module-gradle-plugin")
}
