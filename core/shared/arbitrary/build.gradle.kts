plugins {
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

tasks.withType<Test>().configureEach {
    jvmArgs = listOf("-Xmx4g")
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
}

dependencies {
    api(libs.graphql.java)
    api(libs.kotest.property.jvm)
    api(libs.viaduct.service.runtime)
    api(libs.viaduct.shared.apiannotations)
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.mapping)
    api(libs.viaduct.shared.viaductschema)
    testFixturesCompileOnly(libs.junit) // for @Execution(CONCURRENT) on KotestPropertyBase

    implementation(libs.kotest.common.jvm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.engine.wiring)
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.service.wiring)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.tenant.api)
    implementation(testFixtures(libs.viaduct.service.api))

    testImplementation(libs.kotest.assertions.shared)
    testImplementation(libs.kotest.assertions.core.jvm)
}
