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
    implementation(project(":conventions"))
    implementation(libs.graphql.java)
    testImplementation(gradleTestKit())
    testImplementation(libs.junit.params)
    testImplementation(project(":settings"))
}

gradlePlugin {
    website = "https://viaduct.airbnb.tech"
    vcsUrl = "https://github.com/airbnb/viaduct"
    plugins {
        create("viaductApolloSchema") {
            id = "${libs.versions.pluginIdPrefix.get()}.apollo-schema"
            implementationClass = "viaduct.gradle.apollo.ApolloSchemaSettingsPlugin"
            displayName = "Viaduct :: Apollo Schema Generation"
            description = "Derive Apollo protocol SDL from the validated central schema."
            tags.set(listOf("viaduct", "graphql", "apollo"))
        }
    }
}

viaductPublishing {
    name.set("Apollo Schema Generation")
    description.set("Schema-generation support for Apollo federation; runtime support is installed separately.")
}
