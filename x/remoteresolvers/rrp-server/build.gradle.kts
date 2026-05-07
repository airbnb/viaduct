plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.micronautApplication)
}

micronaut {
    runtime("netty")
    testRuntime("junit")
    processing {
        incremental(true)
    }
}

application {
    mainClass.set("com.example.rrp.service.ApplicationKt")
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

    // rrp-server is a separate included build, so StarWars tenant modules come in
    // via Maven coordinates (substituted by the OSS root settings.gradle.kts).
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
}
