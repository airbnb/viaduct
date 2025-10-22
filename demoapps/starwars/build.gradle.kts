plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSpring)
    alias(libs.plugins.springBoot)
    alias(libs.plugins.dependencyManagement)
    alias(libs.plugins.viaduct.application)
    jacoco
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

viaductApplication {
    grtPackageName.set("viaduct.api.grts")
    modulePackagePrefix.set("com.example.starwars")
}

dependencies {
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.core)
    implementation(libs.spring.boot.starter.graphql)
    implementation(libs.spring.boot.starter.web)

    testImplementation(libs.spring.boot.starter.test) {
        exclude(module = "junit")
    }
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.io.mockk.jvm)
    testImplementation(project(":modules:filmography"))
    testImplementation(project(":modules:universe"))
    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)

    // Add engine-wiring for SchemaFactory support in tests
    testImplementation("com.airbnb.viaduct:engine-wiring")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
}
