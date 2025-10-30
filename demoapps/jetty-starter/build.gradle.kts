plugins {
    kotlin("jvm") version "1.9.24"
    alias(libs.plugins.viaduct.application)
    application
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

dependencies {
    implementation(libs.logback.classic)
    implementation(libs.jackson.databind)

    // Jetty dependencies
    implementation(libs.jetty.server)
    implementation(libs.jetty.servlet)
    implementation(libs.jakarta.servlet.api)

    implementation(project(":resolvers"))

    // Test dependencies
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)
    testImplementation(libs.httpclient5)
}

application {
    mainClass.set("com.example.viadapp.JettyViaductApplicationKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}