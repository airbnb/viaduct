plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.application)
    jacoco
}

viaductApplication {
    modulePackagePrefix.set("com.example")
}

dependencies {
    // Micronaut DI (no HTTP server) - used in production
    ksp(libs.micronaut.inject.kotlin)
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.context)

    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    implementation(libs.kotlin.reflect)

    implementation(project(":common"))
    implementation(project(":viadapp"))

    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)
    // Viaduct's scalar definitions (ExtendedScalars) depend on this at runtime, but it isn't
    // pulled transitively into testRuntimeClasspath, so it must be declared explicitly here.
    testRuntimeOnly(libs.graphql.java.extended.scalars)

    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
