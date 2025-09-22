plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

viaductPublishing {
    name.set("Tenant Runtime")
    description.set("The Viaduct tenant runtime.")
}

dependencies {
    api(libs.graphql.java)
    api(libs.guice)
    api(libs.javax.inject)
    api(libs.viaduct.engine.api)
    api(libs.viaduct.service.api)
    api(libs.viaduct.tenant.api)

    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.classgraph)
    implementation(libs.guava)
    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)

    testFixturesImplementation(libs.viaduct.engine.api)
    testFixturesImplementation(libs.viaduct.engine.runtime)
    testFixturesImplementation(libs.viaduct.service.api)
    testFixturesImplementation(libs.viaduct.tenant.api)
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testFixturesImplementation(libs.graphql.java)
    testFixturesImplementation(libs.io.mockk.jvm)
    testFixturesImplementation(libs.kotlin.reflect)
    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.service.api))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(libs.viaduct.engine.runtime)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.jackson.core)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.module)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.jdk8)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)
    testImplementation(libs.micrometer.core)
}
