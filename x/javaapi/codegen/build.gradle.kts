import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("com.gradleup.shadow")
    application
}

description = "Code generator for Java GRTs (GraphQL Representational Types) from GraphQL schemas"

application {
    mainClass.set("viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator\$Main")
}

dependencies {
    // CLI
    implementation(libs.clikt.jvm)

    // GraphQL parsing
    implementation(libs.graphql.java)

    // ViaductSchema - abstraction layer for GraphQL schema
    implementation(libs.viaduct.shared.viaductschema)

    // Template engine
    implementation(libs.viaduct.shared.codegen)

    // Testing
    testImplementation(libs.assertj.core)
    testImplementation(libs.kotest.assertions.core.jvm)

    // For GraphQLInput interface in exercise tests
    testImplementation(project(":x:javaapi:x-javaapi-api"))
}

// Create fat jar with all dependencies for CLI usage
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("java-grts-codegen")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator\$Main"
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}
