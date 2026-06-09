import viaduct.gradle.resetCoverageThresholds

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

resetCoverageThresholds(instructionMinimum = "0.25", branchMinimum = "0.10")

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.moduleName.set("tenant-wiring")
}

dependencies {
    implementation(libs.graphql.java)
    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.utils)

    implementation(libs.viaduct.service.api)

    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.viaduct.tenant.runtime)

    testImplementation(libs.guice)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(libs.viaduct.engine.api)
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))
}
