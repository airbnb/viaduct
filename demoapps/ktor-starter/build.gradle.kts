plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.viaduct.application)
    jacoco
}

application {
    mainClass.set("com.example.viadapp.ViaductApplicationKt")
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
    // Disable automatic BOM/dependency injection - we manage dependencies explicitly
    applyBOM.set(false)
}

// Create a separate source set for development-only code
sourceSets {
    create("dev") {
        kotlin.srcDir("src/dev/kotlin")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

// Dev source set configurations extend from main
val devImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

val devRuntimeOnly by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.core)

    implementation(libs.ktor.server.core.jvm)
    implementation(libs.ktor.server.jetty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.config.yaml)

    // Koin DI
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    implementation(project(":resolvers"))

    // Development-only: serve dependency for ViaductServer integration
    devImplementation(libs.viaduct.serve)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit5)

    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)

    // Use test fixtures bundle
    testImplementation(libs.viaduct.test.fixtures)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The serve task (from viaduct.application plugin) should include dev classes
tasks.named<JavaExec>("serve") {
    classpath += sourceSets["dev"].output
    classpath += sourceSets["dev"].runtimeClasspath
}
