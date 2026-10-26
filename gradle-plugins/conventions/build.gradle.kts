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
    api(project(":common"))

    bundled(libs.viaduct.shared.graphql)
    bundled(libs.viaduct.shared.bootstrap)
    bundled(libs.viaduct.shared.viaductschema)

    testImplementation(libs.kotest.assertions.core.jvm)
}

gradlePlugin {
    website = "https://viaduct.airbnb.tech"
    vcsUrl = "https://github.com/airbnb/viaduct"
    plugins {
        create("viaductKotlinSettings") {
            id = "${libs.versions.pluginIdPrefix.get()}.kotlinapi"
            implementationClass = "viaduct.gradle.ViaductKotlinSettingsPlugin"
            displayName = "Viaduct :: Kotlin Tenant API"
            description = "Enable the Kotlin tenant API for a Viaduct application."
            tags.set(listOf("viaduct", "graphql", "settings"))
        }
        create("viaductJavaSettings") {
            id = "${libs.versions.pluginIdPrefix.get()}.javaapi"
            implementationClass = "viaduct.gradle.ViaductJavaSettingsPlugin"
            displayName = "Viaduct :: Java Tenant API"
            description = "Enable the Java tenant API for a Viaduct application."
            tags.set(listOf("viaduct", "graphql", "settings"))
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
    name.set("Viaduct Gradle Conventions")
    description.set("Application and module conventions and tenant API settings capabilities.")
}
