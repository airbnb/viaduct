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
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.viaductschema)
    api(libs.viaduct.shared.mapping)
    api(libs.viaduct.shared.apiannotations)
    testFixturesImplementation(libs.junit) // for @Execution(CONCURRENT) on KotestPropertyBase

    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.service.wiring)
    implementation(libs.viaduct.engine.wiring)
    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.kotest.common.jvm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.kotest.assertions.shared)
}
