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
    // Reuse AssembleSchemaPartitionTask + ViaductModuleExtension from the Kotlin module plugin
    implementation(project(":module"))

    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.viaductschema)

    testImplementation(gradleTestKit())
    testImplementation(project(":application"))
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

    val pluginIdPrefix: String by rootProject.extra

    plugins {
        create("viaductJavaModule") {
            id = "$pluginIdPrefix.module-java-gradle-plugin"
            implementationClass = "viaduct.gradle.ViaductJavaModulePlugin"
            displayName = "Viaduct :: Java Module Plugin"
            description = "Module plugin for Viaduct tenant modules written in Java."
            tags.set(listOf("viaduct", "graphql", "java"))
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
    name.set("Java Module Gradle Plugin")
    description.set("Gradle plugin for Viaduct tenant modules written in Java.")
}
