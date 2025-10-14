plugins {
    id("conventions.kotlin")
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

    testImplementation(libs.kotlin.test)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.kotest.assertions.shared)
}
