plugins {
    `java-library`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.viaduct.module)
}

dependencies {
    api(libs.viaduct.api)
    implementation(libs.viaduct.runtime)
}
