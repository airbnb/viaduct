plugins {
    `java-library`
    alias(libs.plugins.micronautApplication)
    alias(libs.plugins.viaduct.application)
    jacoco
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
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
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.javaapi.runtime)
    implementation(libs.viaduct.runtime)

    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.reactor.core)
    implementation(libs.micronaut.http.server.netty)
    implementation(libs.micronaut.jackson.databind)
    implementation(libs.micronaut.inject)
    annotationProcessor(libs.micronaut.inject.java)

    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.logback.classic)
    implementation(project(":common"))

    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.micronaut.test.junit5)
    testImplementation(libs.micronaut.http.client)
    testImplementation(libs.viaduct.test.fixtures)
    testImplementation(project(":modules:filmography"))
    testImplementation(project(":modules:universe"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "com.example.starwars.service.Application"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.withType<JavaExec> {
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
}
