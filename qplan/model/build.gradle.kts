plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.viaduct.engine.api)
    api(libs.viaduct.shared.viaductschema)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testFixturesImplementation("com.graphql-java:graphql-java:26.0")
    testFixturesImplementation("com.google.inject:guice:7.0.0")
    testFixturesImplementation("jakarta.inject:jakarta.inject-api:2.0.1")
    testFixturesApi(libs.viaduct.shared.graphql)
    testFixturesApi(libs.viaduct.shared.utils)
    testFixturesApi(libs.viaduct.shared.viaductschema)

    testImplementation(kotlin("test-junit5"))
}
