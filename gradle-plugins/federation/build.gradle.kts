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
    implementation(project(":conventions"))

    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://viaduct.airbnb.tech"
    vcsUrl = "https://github.com/airbnb/viaduct"
    plugins {
        create("foundationFederation") {
            id = "${libs.versions.pluginIdPrefix.get()}.foundation-federation"
            implementationClass = "viaduct.gradle.federation.FoundationFederationSettingsPlugin"
            displayName = "Viaduct :: Foundation Federation"
            description = "Enable GraphQL Foundation federation field-selection validation."
            tags.set(listOf("viaduct", "graphql", "federation", "settings"))
        }
    }
}

tasks.named<ProcessResources>("processResources") {
    val pluginVersion = project.version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("viaduct-foundation-version.properties") {
        expand("version" to pluginVersion)
    }
}

viaductPublishing {
    name.set("Foundation Federation Gradle Plugin")
    description.set("GraphQL Foundation federation field-selection validation and schema definitions.")
}
