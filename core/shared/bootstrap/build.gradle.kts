plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

// Keep this list short: producers of bootstrap data inherit whatever is added here. See README.md.
dependencies {
    implementation(libs.jackson.annotations)
    implementation(libs.jackson.module)
    implementation(libs.viaduct.shared.apiannotations)

    testImplementation(libs.kotest.assertions.core.jvm)
}
