plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.viaduct.application)
    jacoco
}

viaductApplication {
    // The starter's `resolvers` module ships an SDL with `@scope(to: ["default"])` on its
    // Query/Mutation extensions, so opt into schema scoping. Post-slice-4 the build-time
    // central-SDL assembler only emits the `@scope` directive definition when the app
    // declares a scope universe; without this block the assembler drops `directive @scope`
    // and graphql-java rejects the tenant SDL with "Unknown directive '@scope'".
    declareScoping {
        scopes("default")
    }
}

application {
    mainClass.set("com.example.viadapp.ViaductServiceKt")
}

dependencies {
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.core)

    implementation(libs.ktor.server.core.jvm)
    implementation(libs.ktor.server.jetty.jakarta)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.config.yaml)

    runtimeOnly(libs.logback.classic)

    // Import JUnit BOM to control all JUnit versions consistently
    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)

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
