
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(gradleApi())

    // ViaductApplicationExtension exposes Provider<SchemaScoping> from service-api as the
    // canonical scope-state surface, so the type must be on the api classpath.
    api(libs.viaduct.service.api)

    implementation(libs.idea.gradle.plugin)
}

viaductPublishing {
    name.set("Common Gradle Plugin Libraries")
    description.set("Common libs used by Viaduct Gradle plugins.")
}
