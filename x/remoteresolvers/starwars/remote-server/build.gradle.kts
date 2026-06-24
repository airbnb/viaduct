plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.jackson.module.kotlin)

    // Viaduct core - fat jars bundle all engine, tenant, and service classes
    implementation(libs.viaduct.api)
    implementation(libs.viaduct.runtime)
    implementation(libs.kotlinx.coroutines.jdk8)

    // Remote Resolver module - provides RemoteResolverServiceImpl and registries
    implementation(libs.viaduct.x.remoteresolvers)

    // StarWars tenant modules - the actual resolvers to execute remotely
    // These are resolved via includeBuild in settings.gradle.kts
    implementation("com.example.starwars:common")
    implementation("com.example.starwars:filmography")
    implementation("com.example.starwars:universe")

    // Guice for dependency injection (needed to create resolver instances)
    implementation(libs.guice)

    // Micronaut - needed for RequestScope annotation binding
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.http)

    // gRPC server dependencies
    implementation(libs.grpc.netty.shaded)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.grpc.services)  // For reflection service
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("com.example.remote.ApplicationKt")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaExec> {
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Xms512m", "-Xmx1g", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=20")
}
