plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":model"))
    implementation(project(":semantics"))
    implementation(testFixtures(project(":model")))
    implementation("com.graphql-java:graphql-java:26.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.1")

    testFixturesImplementation(project(":model"))
    testFixturesImplementation(testFixtures(project(":model")))
    testFixturesApi(testFixtures(project(":semantics")))
    testFixturesImplementation("com.graphql-java:graphql-java:26.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(libs.viaduct.engine.wiring)
    testFixturesImplementation(testFixtures(libs.viaduct.shared.graphql))
    testFixturesImplementation(kotlin("test"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.shared.arbitrary))
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(libs.viaduct.engine.runtime)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(libs.viaduct.shared.graphql)
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.viaduct.service.api)
    testImplementation(testFixtures(libs.viaduct.service.api))
}
