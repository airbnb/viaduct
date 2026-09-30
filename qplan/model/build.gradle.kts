plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-library`
    `java-test-fixtures`
}

dependencies {
    api(libs.viaduct.engine.api)
    api(libs.viaduct.shared.viaductschema)
    api(libs.viaduct.shared.graphql)
    api(libs.graphql.java)
    implementation(libs.kotlinx.coroutines.core)

    testFixturesImplementation(libs.graphql.java)
    testFixturesImplementation(libs.guice)
    testFixturesImplementation(libs.jakarta.inject)
    testFixturesApi(libs.viaduct.shared.utils)
    testFixturesApi(libs.viaduct.shared.viaductschema)

    testImplementation(kotlin("test-junit5"))
}
