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
}
