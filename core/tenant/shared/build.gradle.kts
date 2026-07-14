plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    api(libs.viaduct.shared.apiannotations)
    api(libs.viaduct.service.api)
    implementation(libs.viaduct.errors)
}
