plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(project(":build-common"))
    implementation(project(":build-shared"))

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)
}
