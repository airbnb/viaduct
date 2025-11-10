plugins {
    `java-library`
    id("conventions.kotlin")
    `maven-publish`
    `java-test-fixtures`
    id("conventions.kotlin-static-analysis")
    id("conventions.viaduct-publishing")
    id("jacoco-integration-base")
}

viaductPublishing {
    name.set("Tenant API")
    description.set("Viaduct Tenant API")
}

dependencies {
    implementation(libs.graphql.java)
    implementation(libs.javax.inject)
    implementation(libs.viaduct.engine.api)

    implementation(libs.guava)

    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.viaductschema)
    implementation(libs.viaduct.shared.mapping)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)

    testFixturesApi(libs.viaduct.engine.api)
    testFixturesApi(libs.graphql.java)
    testFixturesApi(libs.viaduct.shared.viaductschema)

    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(libs.viaduct.tenant.runtime)

    testImplementation(libs.graphql.java.extension)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)

    testImplementation(libs.strikt.core)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.shared.mapping))
}
