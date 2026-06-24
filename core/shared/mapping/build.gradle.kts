plugins {
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
}

dependencies {
    api(libs.graphql.java)
    api(libs.viaduct.shared.apiannotations)
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.viaductschema)

    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.kotest.common.jvm)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.graphql.java.extension)
    testImplementation(libs.kotest.assertions.shared)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.shared.arbitrary))

    testFixturesImplementation(libs.kotest.property.jvm)
    testFixturesImplementation(libs.viaduct.shared.arbitrary)
    testFixturesImplementation(testFixtures(libs.viaduct.shared.arbitrary))
}
