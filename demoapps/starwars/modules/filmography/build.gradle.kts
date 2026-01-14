plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("filmography")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.micronaut.inject)
    kapt(libs.micronaut.inject.java)
    kapt(libs.micronaut.inject.kotlin)
}
