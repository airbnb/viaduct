plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.application)
    application
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

dependencies {
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    implementation(libs.logback.classic)
    implementation(libs.jackson.databind)

    // Coroutines for async execution
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.kotlin.reflect)
    implementation(libs.reactive.streams)

    // Kotest common for runBlocking (used in ViaductServlet)
    implementation(libs.kotest.common)

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

    // Use test fixtures bundle
    testImplementation(libs.viaduct.test.fixtures)
}

application {
    mainClass.set("com.example.viadapp.JettyViaductApplicationKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
