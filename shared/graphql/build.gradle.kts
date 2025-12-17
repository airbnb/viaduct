plugins {
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    api(libs.graphql.java)

    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.apiannotations)
    implementation(libs.graphql.java.extension)
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)

    testFixturesApi(libs.graphql.java)

    testFixturesImplementation(libs.io.mockk.dsl)
    testFixturesImplementation(libs.jackson.core)
    testFixturesImplementation(libs.jackson.databind)
    testFixturesImplementation(libs.jackson.module)
    testFixturesImplementation(libs.kotest.assertions.shared)
    testFixturesImplementation(libs.kotlin.test)
    testFixturesImplementation(libs.viaduct.shared.invariants)

    testImplementation(libs.guava)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.assertions.core.jvm)
}
