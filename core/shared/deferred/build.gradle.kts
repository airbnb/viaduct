import viaduct.gradle.resetCoverageThresholds

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

resetCoverageThresholds(instructionMinimum = "0.65")

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    api(libs.viaduct.shared.apiannotations)

    testImplementation(libs.kotlinx.coroutines.test)
}
