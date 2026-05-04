import viaduct.gradle.resetCoverageThresholds

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
}

// Module is being removed (PR 1) — don't enforce coverage.
resetCoverageThresholds(instructionMinimum = "0.00", branchMinimum = "0.00")

dependencies {
    api(libs.graphql.java)
    api(libs.viaduct.shared.apiannotations)
    implementation(libs.viaduct.errors)

    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
}
