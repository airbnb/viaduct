import viaduct.gradle.resetCoverageThresholds

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
    `java-test-fixtures`
}

resetCoverageThresholds(instructionMinimum = "0.25", branchMinimum = "0.10")

dependencies {
    implementation(libs.graphql.java)
    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.utils)

    implementation(libs.viaduct.service.api)

    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.viaduct.tenant.runtime)
    implementation(libs.jackson.module)

    testImplementation(libs.guice)
    testImplementation(libs.viaduct.service.runtime)
    testFixturesImplementation(libs.viaduct.engine.api)
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))
}
