
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(gradleApi())

    // service-api hosts the schema scope definition model and its rules, which cross module
    // boundaries: ViaductScopesYaml here decodes scopes.yaml against them, and :application reads the
    // result inside AssembleCentralSchemaTask. Exposed via `api` so :application can name the types
    // directly; @InternalApi and @ExperimentalApi on each member keep BCV's public-surface listing
    // unchanged.
    api(libs.viaduct.service.api)

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
