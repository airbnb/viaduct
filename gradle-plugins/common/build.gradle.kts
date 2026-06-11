
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(gradleApi())

    // SchemaScoping is the build-to-runtime API contract emitted by the plugin DSL. It
    // appears in the public API of ViaductApplicationExtension, so expose it via `api`.
    api(libs.viaduct.service.api)

    implementation(libs.idea.gradle.plugin)
}

viaductPublishing {
    name.set("Common Gradle Plugin Libraries")
    description.set("Common libs used by Viaduct Gradle plugins.")
}
