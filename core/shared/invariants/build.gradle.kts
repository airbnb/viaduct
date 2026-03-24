plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.viaduct.shared.apiannotations)

    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotest.assertions.shared)
}
