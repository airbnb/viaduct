plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

// Keep this list short: the Gradle plugins bundle this module and inherit whatever is added here. See README.md.
dependencies {
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.viaduct.shared.apiannotations)
}
