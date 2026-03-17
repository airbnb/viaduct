plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    api(libs.viaduct.shared.apiannotations)

    testImplementation(libs.kotlinx.coroutines.test)
}
