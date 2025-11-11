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
    testImplementation(libs.micronaut.http.client)

    // Test fixtures: use individual modules when part of composite, shaded when standalone
    if (gradle.parent != null) {
        testImplementation(testFixtures("com.airbnb.viaduct:engine-api"))
        testImplementation(testFixtures("com.airbnb.viaduct:tenant-runtime"))
    } else {
        testImplementation("com.airbnb.viaduct:viaduct-shaded::test-fixtures")
    }
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

