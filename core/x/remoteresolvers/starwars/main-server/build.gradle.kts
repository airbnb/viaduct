import viaduct.gradle.task.GenerateExecutionRegistryResourceIndexTask

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.micronautApplication)
    id("com.airbnb.viaduct.application-gradle-plugin") apply false
}

val executionRegistryResourceIndex =
    tasks.register<GenerateExecutionRegistryResourceIndexTask>("generateViaductExecutionRegistryResourceIndex") {
        moduleClasspath.from(configurations.named("runtimeClasspath"))
        outputDirectory.set(layout.buildDirectory.dir("generated-resources/viaduct-execution-registry-index"))
    }

sourceSets.named("main") {
    resources.srcDir(executionRegistryResourceIndex.flatMap { it.outputDirectory })
}

micronaut {
    runtime("netty")
    testRuntime("junit")
    processing {
        incremental(true)
    }
}

application {
    mainClass.set("com.example.main.service.ApplicationKt")
}

configurations.all {
    resolutionStrategy {
        force(libs.guice)
    }
}

dependencies {
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)
    implementation(libs.viaduct.x.remoteresolvers)

    // StarWars tenant modules are not published; they are provided from source via the
    // includeBuild + dependencySubstitution in settings.gradle.kts.
    implementation("com.example.starwars:common")
    runtimeOnly("com.example.starwars:filmography")
    runtimeOnly("com.example.starwars:universe")

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

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
