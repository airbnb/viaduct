plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    // Viaduct dependencies
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.service.wiring)
    implementation(libs.viaduct.tenant.api)

    // GraphQL
    implementation(libs.graphql.java)

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.jackson)

    // Classpath scanning for finding @ViaductServerConfiguration
    implementation(libs.classgraph)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core.jvm)

    // JSON
    implementation(libs.jackson.module)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}

viaductPublishing {
    name.set("Viaduct Serve")
    description.set("Development server runtime for Viaduct GraphQL applications with GraphiQL IDE")
    artifactId.set("serve")
}
