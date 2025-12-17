plugins {
    `java-library`
    id("conventions.kotlin")
    `maven-publish`
    `java-test-fixtures`
    id("conventions.kotlin-static-analysis")
    id("conventions.dokka")
    id("jacoco-integration-base")
}

viaductPublishing {
    name.set("Tenant API")
    description.set("Viaduct Tenant API")
}

dependencies {
    /** Viaduct dependencies **/
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.apiannotations)
    implementation(libs.viaduct.shared.mapping)
    implementation(libs.viaduct.shared.apiannotations)

    /** External dependencies **/
    implementation(libs.graphql.java)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.databind)
    implementation(libs.kotlinx.coroutines.core)

    /** Test fixtures - Viaduct dependencies **/
    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(libs.viaduct.tenant.runtime)

    /** Test fixtures - External dependencies **/
    testFixturesApi(libs.junit)

    /** Test dependencies - Viaduct **/
    testImplementation(testFixtures(libs.viaduct.shared.mapping))

    /** Test dependencies - External **/
    testImplementation(libs.graphql.java.extension)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
}
