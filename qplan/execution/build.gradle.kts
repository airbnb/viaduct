plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":model"))
    implementation(project(":semantics"))
    implementation(testFixtures(project(":model")))
    implementation(libs.graphql.java)
    implementation(libs.kotlinx.coroutines.jdk8)

    testFixturesImplementation(project(":model"))
    testFixturesImplementation(testFixtures(project(":model")))
    testFixturesApi(testFixtures(project(":semantics")))
    testFixturesImplementation(libs.graphql.java)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(libs.viaduct.engine.wiring)
    testFixturesImplementation(testFixtures(libs.viaduct.shared.graphql))
    testFixturesImplementation(kotlin("test"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.shared.arbitrary))
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(libs.viaduct.engine.runtime)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(libs.viaduct.shared.graphql)
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.viaduct.service.api)
    testImplementation(testFixtures(libs.viaduct.service.api))
}
