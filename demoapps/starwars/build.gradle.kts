plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.micronautApplication)
    alias(libs.plugins.viaduct.application)
    jacoco
}

viaductApplication {
    // Starwars uses `@scope` densely across Character, Film, Species, Planet, Vehicle, and
    // Starship, so opt into schema scoping. Declaring the full universe here makes
    // `@scope` available at build time and lets `ScopeUsageRule` validate directive usages.
    // `modulePackagePrefix` is now declared in `settings.gradle.kts` via
    // `includeViaductApplication { modulePackagePrefix(...) }`.
    declareScoping {
        scopes("default", "extras")
    }
}

micronaut {
    runtime("netty")
    testRuntime("junit")
    processing {
        incremental(true)
    }
}

allprojects {
    plugins.withId("org.jetbrains.kotlin.kapt") {
        extensions.configure<org.jetbrains.kotlin.gradle.plugin.KaptExtension> {
            arguments {
                arg("micronaut.processing.incremental", "true")
            }
        }
    }
}

configurations.all {
    resolutionStrategy {
        force(libs.guice)
    }
}

dependencies {
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.core)
    implementation(libs.micronaut.http.server.netty)
    implementation(libs.micronaut.jackson.databind)
    implementation(libs.micronaut.inject)

    kapt(libs.micronaut.inject.java)
    kapt(libs.micronaut.inject.kotlin)

    runtimeOnly(libs.logback.classic)
    implementation(project(":common"))

    // Import JUnit BOM to control all JUnit versions consistently
    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.micronaut.test.kotest5)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(project(":modules:filmography"))
    testImplementation(project(":modules:universe"))
    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)
    testImplementation(libs.micronaut.http.client)
    testImplementation(libs.viaduct.test.fixtures)
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
