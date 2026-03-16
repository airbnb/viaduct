plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(project(":common"))
    implementation(project(":shared"))

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)
}
