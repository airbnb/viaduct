plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.micronautApplication)
    alias(libs.plugins.viaduct.application)
    jacoco
}

viaductApplication {
    grtPackageName.set("viaduct.api.grts")
    modulePackagePrefix.set("com.example.starwars")
}

micronaut {
    runtime("netty")
    testRuntime("junit")
    processing {
        incremental(true)
    }
}

configurations.all {
    resolutionStrategy {
        force(libs.guice)
    }
}

dependencies {
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.core)
    implementation(libs.micronaut.graphql)
    implementation(libs.micronaut.http.server.netty)
    implementation(libs.micronaut.jackson.databind)
    implementation(libs.micronaut.inject)

    kapt(libs.micronaut.inject.java)
    kapt(libs.micronaut.inject.kotlin)

    runtimeOnly(libs.logback.classic)
    implementation(project(":common"))
    runtimeOnly(project(":modules:filmography"))
    runtimeOnly(project(":modules:universe"))

    testImplementation(libs.micronaut.test.kotest5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(project(":modules:filmography"))
    testImplementation(project(":modules:universe"))
    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(libs.micronaut.http.client)
    testImplementation(testFixtures(libs.viaduct.tenant.api))
}

application {
    mainClass = "com.example.starwars.service.ApplicationKt"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
}

