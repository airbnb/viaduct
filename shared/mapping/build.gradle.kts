plugins {
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    api(libs.graphql.java)
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.viaductschema)

    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.kotest.common.jvm)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.graphql.java.extension)
    testImplementation(libs.kotest.assertions.shared)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.engine.api))

    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotest.property.jvm)
    testFixturesImplementation(libs.viaduct.shared.arbitrary)
}
