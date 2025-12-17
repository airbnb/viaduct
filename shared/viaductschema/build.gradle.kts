plugins {
    id("conventions.kotlin")
    `java-test-fixtures`
    id("conventions.kotlin-static-analysis")
}

tasks.test {
    environment("PACKAGE_WITH_SCHEMA", "invalidschemapkg")
}

dependencies {
    api(libs.graphql.java)
    api(libs.junit)
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.graphql)
    api(libs.viaduct.shared.utils)

    implementation(libs.guava)
    implementation(libs.kotlin.reflect)
    implementation(libs.reflections)
    implementation(libs.jspecify)
    implementation(libs.viaduct.shared.apiannotations)

    testFixturesApi(libs.graphql.java)
    testFixturesApi(libs.junit)
    testFixturesApi(libs.viaduct.shared.invariants)

    testFixturesImplementation(libs.guava)
    testFixturesImplementation(libs.kotlin.reflect)
    testFixturesImplementation(libs.reflections)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.kotest.assertions.shared)
}
