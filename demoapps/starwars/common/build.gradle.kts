plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinKapt)
}

dependencies {
    implementation(libs.micronaut.inject)
    implementation(libs.micronaut.http)

    kapt(libs.micronaut.inject.java)
    kapt(libs.micronaut.inject.kotlin)
}
