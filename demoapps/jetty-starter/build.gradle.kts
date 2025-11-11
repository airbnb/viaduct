plugins {
    kotlin("jvm") version "1.9.24"
    alias(libs.plugins.viaduct.application)
    application
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

dependencies {
    // Import Viaduct BOM for version management
    implementation(platform("com.airbnb.viaduct:viaduct-bom:${libs.versions.viaduct.get()}"))

    // When part of composite build: use individual Viaduct modules from repo
    // When standalone: use shaded jar from Maven Central
    if (gradle.parent != null) {
        // Use individual modules when part of composite build
        implementation("com.airbnb.viaduct:engine-api")
        implementation("com.airbnb.viaduct:engine-runtime")
        implementation("com.airbnb.viaduct:tenant-api")
        implementation("com.airbnb.viaduct:tenant-runtime")
    } else {
        // Use shaded jar when standalone
        implementation("com.airbnb.viaduct:viaduct-shaded::runtime")
    }

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