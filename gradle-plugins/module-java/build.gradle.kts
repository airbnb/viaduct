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
    implementation(project(":common"))

    // Classes bundled directly into this plugin JAR via conventions.viaduct-fat-plugin.
    bundled(libs.viaduct.shared.graphql)
    bundled(libs.viaduct.shared.viaductschema)

    testImplementation(gradleTestKit())
    testImplementation(project(":application"))
    testImplementation(project(":settings"))
    testImplementation(libs.viaduct.javaapi.runtime)
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
        create("viaductJavaModule") {
            id = "${libs.versions.pluginIdPrefix.get()}.module-java-gradle-plugin"
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
