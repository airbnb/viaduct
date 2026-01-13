plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("filmography")
    // Disable automatic BOM/dependency injection - we manage dependencies explicitly
    applyBOM.set(false)
}

dependencies {
    api(libs.viaduct.api)
    implementation(libs.viaduct.runtime)

    // Local starwars common module
    implementation(project(":common"))
    implementation(libs.micronaut.inject)
    kapt(libs.micronaut.inject.java)
    kapt(libs.micronaut.inject.kotlin)
}
