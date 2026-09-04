plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    implementation(libs.viaduct.shared.arbitrary)
    implementation(libs.clikt.jvm)
}
