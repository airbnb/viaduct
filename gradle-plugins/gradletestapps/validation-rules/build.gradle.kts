plugins {
    id("conventions.kotlin")
}

dependencies {
    compileOnly(libs.viaduct.gradle.plugins.common)
    implementation(project(":validation-rule-support"))
}
