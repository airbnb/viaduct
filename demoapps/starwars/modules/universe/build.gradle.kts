plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("universe")
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
