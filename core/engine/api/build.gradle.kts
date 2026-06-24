import viaduct.gradle.resetCoverageThresholds

plugins {
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

resetCoverageThresholds(instructionMinimum = "0.35", branchMinimum = "0.40")

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.moduleName.set("engine-api")
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestFixturesKotlin") {
    compilerOptions.moduleName.set("engine-api_testFixtures")
}

dependencies {
    /** External dependencies **/
    implementation(libs.graphql.java)
    implementation(libs.guice)
    implementation(libs.caffeine)
    implementation(libs.jackson.annotations)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)

    /** Viaduct dependencies **/
    api(libs.viaduct.service.api)
    api(libs.viaduct.errors)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.apiannotations)
    /** Test fixtures - Viaduct dependencies **/
    testFixturesApi(libs.viaduct.service.runtime)
    testFixturesApi(libs.viaduct.service.wiring)
    testFixturesApi(testFixtures(libs.viaduct.engine.runtime))
    testFixturesImplementation(libs.viaduct.engine.wiring)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.viaduct.service.api)
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(testFixtures(libs.viaduct.shared.dataloader))
    testFixturesImplementation(testFixtures(libs.viaduct.shared.graphql))

    /** Test fixtures - External dependencies (implementation) **/
    testFixturesImplementation(libs.kotlinx.coroutines.jdk8)
    testFixturesImplementation(libs.kotlinx.coroutines.test)

    /** Test dependencies - External **/
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.assertions.core.jvm)
}
