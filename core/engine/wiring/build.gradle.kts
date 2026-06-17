import viaduct.gradle.resetCoverageThresholds

plugins {
    id("conventions.kotlin")
}

resetCoverageThresholds(instructionMinimum = "0.25", branchMinimum = "0.25")

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.moduleName.set("engine-wiring")
}

dependencies {

    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.shared.apiannotations)
    implementation(libs.viaduct.shared.deferred)
    implementation(libs.viaduct.shared.utils)

    implementation(libs.guice)
    implementation(libs.graphql.java)
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.classgraph)
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.micrometer.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(testFixtures(libs.viaduct.engine.api))
}
