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
}


dependencies {
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

    implementation(project(":resolvers"))

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
