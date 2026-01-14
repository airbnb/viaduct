plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)  // Add this
    alias(libs.plugins.viaduct.module)
}

viaductModule {
    modulePackageSuffix.set("universe")
}

dependencies {
    implementation(project(":common"))
    implementation(libs.micronaut.inject)
    kapt(libs.micronaut.inject.java)
    kapt(libs.micronaut.inject.kotlin)
}
