plugins {
    `kotlin-dsl`
    id("conventions.kotlin-static-analysis")
}

dependencies {
    implementation(libs.viaduct.tenant.codegen)
    implementation(libs.viaduct.shared.viaductschema)
    implementation(libs.viaduct.gradle.plugins.common)

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)
}
