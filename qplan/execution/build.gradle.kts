plugins {
    kotlin("jvm")
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
    testFixturesImplementation(testFixtures(viaductLibs.viaduct.engine.api))
    testFixturesImplementation(viaductLibs.viaduct.engine.wiring)
    testFixturesImplementation(testFixtures(viaductLibs.viaduct.shared.graphql))
    testFixturesImplementation(kotlin("test"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(viaductLibs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(viaductLibs.viaduct.shared.arbitrary))
    testImplementation(viaductLibs.kotest.assertions.core.jvm)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation(testFixtures(viaductLibs.viaduct.engine.api))
    testImplementation(viaductLibs.viaduct.engine.runtime)
    testImplementation(viaductLibs.viaduct.engine.wiring)
    testImplementation(viaductLibs.viaduct.shared.graphql)
    testImplementation(testFixtures(viaductLibs.viaduct.shared.graphql))
    testImplementation(viaductLibs.viaduct.service.api)
    testImplementation(testFixtures(viaductLibs.viaduct.service.api))
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
