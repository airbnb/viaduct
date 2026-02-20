plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

description = "Java Tenant API runtime implementation - bridges Java API to Kotlin engine"

dependencies {
    // Java API that this runtime implements
    api(project(":x:javaapi:x-javaapi-api"))

    // Viaduct engine API (Kotlin)
    api(libs.viaduct.engine.api)

    // Viaduct service API (for TenantCodeInjector)
    api(libs.viaduct.service.api)

    // Kotlin coroutines for async bridging
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8) // For CompletableFuture integration

    // Shared ClassGraph scanner utility for classpath scanning
    implementation(libs.viaduct.shared.utils)

    // javax.inject for Provider interface
    implementation(libs.javax.inject)

    // Logging
    implementation(libs.slf4j.api)

    // GraphQL schema types
    implementation(libs.graphql.java)

    // Testing
    testImplementation(libs.assertj.core)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.viaduct.engine.runtime)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.graphql.java)
}
