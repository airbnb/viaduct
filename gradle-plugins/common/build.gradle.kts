
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("conventions.gradle-plugin-kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(gradleApi())
    api(libs.viaduct.build.shared)

    implementation(libs.idea.gradle.plugin)
}

viaductPublishing {
    name.set("Common Gradle Plugin Libraries")
    description.set("Common libs used by Viaduct Gradle plugins.")
}
