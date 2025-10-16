
plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    api(gradleApi())
    implementation(libs.idea.gradle.plugin)
}

viaductPublishing {
    artifactId.set("gradle-plugins-common")
    name.set("Common Gradle Plugin Libraries")
    description.set("Common libs used by Viaduct Gradle plugins.")
}
