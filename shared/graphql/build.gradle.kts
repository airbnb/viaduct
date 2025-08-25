plugins {
    `java-test-fixtures`
    id("kotlin-project")
    id("kotlin-static-analysis")
}

dependencies {
    api(libs.graphql.java)

    implementation(project(":shared:utils"))
    implementation(libs.graphql.java.extension)
    implementation(libs.slf4j.api)

    testFixturesApi(libs.graphql.java)

    testFixturesImplementation(libs.io.mockk.dsl)
    testFixturesImplementation(libs.jackson.core)
    testFixturesImplementation(libs.jackson.databind)
    testFixturesImplementation(libs.jackson.module)
    testFixturesImplementation(libs.kotest.assertions.shared)
    testFixturesImplementation(libs.kotlin.test)

    testImplementation(libs.guava)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.assertions.core.jvm)
}
