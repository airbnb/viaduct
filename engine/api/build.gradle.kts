plugins {
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

viaductPublishing {
    name.set("Engine API")
    description.set("The API exposed by the Viaduct engine.")
}

dependencies {
    /** External dependencies **/
    implementation(libs.graphql.java)
    implementation(libs.guice)
    implementation(libs.caffeine)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)

    /** Viaduct dependencies **/
    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.apiannotations)
    implementation(libs.viaduct.snipped.errors)

    /** Test fixtures - Viaduct dependencies **/
    testFixturesApi(libs.viaduct.service.runtime)
    testFixturesApi(libs.viaduct.service.wiring)
    testFixturesApi(testFixtures(libs.viaduct.engine.runtime))
    testFixturesImplementation(libs.viaduct.engine.wiring)
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(testFixtures(libs.viaduct.shared.dataloader))
    testFixturesImplementation(testFixtures(libs.viaduct.shared.graphql))

    /** Test fixtures - External dependencies (implementation) **/
    testFixturesImplementation(libs.kotlinx.coroutines.jdk8)
    testFixturesImplementation(libs.kotlinx.coroutines.test)

    /** Test dependencies - External **/
    testImplementation(libs.io.mockk.jvm)
}
