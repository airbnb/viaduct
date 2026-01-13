plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.application)
    jacoco
}

viaductApplication {
    modulePackagePrefix.set("com.example")
}

// Create a separate source set for development-only code
sourceSets {
    create("dev") {
        kotlin.srcDir("src/dev/kotlin")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

// Dev source set configurations extend from main
val devImplementation by configurations.getting {
    extendsFrom(configurations.implementation.get())
}

val devRuntimeOnly by configurations.getting {
    extendsFrom(configurations.runtimeOnly.get())
}

dependencies {
    // Micronaut DI (no HTTP server) - used in production
    ksp(libs.micronaut.inject.kotlin)
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.context)

    implementation(libs.kotlin.reflect)

    implementation(project(":viadapp"))

    // Development-only: serve dependency for ViaductServer integration
    devImplementation(libs.viaduct.serve)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platform.launcher)

    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The serve task (from viaduct.application plugin) should include dev classes
tasks.named<JavaExec>("serve") {
    classpath += sourceSets["dev"].output
    classpath += sourceSets["dev"].runtimeClasspath
}
