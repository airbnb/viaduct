
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(gradleApi())

    // SchemaScoping is used internally by plugin tasks for manifest serialization and is not
    // part of the public Gradle plugin API, so service-api is an implementation detail only.
    implementation(libs.viaduct.service.api)

    implementation(libs.idea.gradle.plugin)

    testImplementation(gradleTestKit())
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
