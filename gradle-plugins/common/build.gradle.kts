
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(gradleApi())

    // service-api hosts SchemaScoping and its validator, both of which cross module
    // boundaries: the application plugin in :application reads schemaScoping (for validation
    // and as a typed task input — see AssembleCentralSchemaTask), and the runtime engine
    // deserializes the manifest JSON back into SchemaScoping. Exposed via `api` so :application
    // can reference the types directly; @InternalApi on each member keeps BCV's public-surface
    // listing unchanged.
    api(libs.viaduct.service.api)

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
