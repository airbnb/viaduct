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
    implementation(libs.graphql.java)
    implementation(libs.guice)
    implementation(libs.javax.inject)
    implementation(libs.viaduct.shared.utils)

    implementation(libs.caffeine)
    implementation(libs.checker.qual)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.snipped.errors)

    testFixturesApi(libs.graphql.java)
    testFixturesApi(libs.viaduct.engine.runtime)
    testFixturesApi(libs.viaduct.service.runtime)
    testFixturesApi(libs.viaduct.service.wiring)
    testFixturesApi(testFixtures(libs.viaduct.engine.runtime))

    testFixturesImplementation(libs.viaduct.engine.wiring)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.viaduct.service.api)
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(testFixtures(libs.viaduct.shared.dataloader))
    testFixturesImplementation(testFixtures(libs.viaduct.shared.graphql))

    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.viaduct.engine.runtime)
    testImplementation(testFixtures(libs.viaduct.engine.runtime))
}
