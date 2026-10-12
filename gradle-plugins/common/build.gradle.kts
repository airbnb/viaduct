
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
    id("conventions.viaduct-fat-plugin")
}

dependencies {
    api(gradleApi())

    // shared.scoping and shared.apiannotations have no published artifact, so this module bundles their
    // classes; `api` lets the plugins that depend on it name the scoping types directly.
    bundled(libs.viaduct.shared.scoping)
    api(libs.viaduct.shared.scoping)
    api(libs.viaduct.shared.apiannotations)

    implementation(libs.jackson.module)
    implementation(libs.jackson.dataformat.yaml)

    implementation(libs.idea.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(libs.kotest.assertions.core.jvm)
}

// ProjectBuilder requires this open on Java 17+ (kotlin-dsl adds it automatically for plugin
// projects, but common uses org.jetbrains.kotlin.jvm instead).
tasks.named<Test>("test") {
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}

viaductPublishing {
    name.set("Common Gradle Plugin Libraries")
    description.set("Common libs used by Viaduct Gradle plugins.")
}
