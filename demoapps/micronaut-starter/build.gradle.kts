plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.application)
    jacoco
}

viaductApplication {
    modulePackagePrefix.set("com.example.viadapp")
}

dependencies {
    // Micronaut DI (no HTTP server)
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.context)

    implementation(libs.kotlin.reflect)

    implementation(project(":resolvers"))

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
