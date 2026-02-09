plugins {
    id("conventions.kotlin")
    id("me.champeau.jmh").version("0.7.3")
    `java-test-fixtures`
    id("conventions.kotlin-static-analysis")
}

viaductPublishing {
    name.set("Engine Runtime")
    description.set("The Viaduct engine runtime.")
}

dependencies {
    implementation(libs.graphql.java)
    implementation(libs.jackson.annotations)
    implementation(libs.javax.inject)
    implementation(libs.kotlinx.coroutines.core.jvm)

    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.shared.dataloader)
    implementation(libs.viaduct.shared.utils)

    implementation(libs.caffeine)
    implementation(libs.checker.qual)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.slf4j.api)
    implementation(libs.viaduct.shared.deferred)
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.logging)
    implementation(libs.viaduct.snipped.errors)
    implementation(libs.viaduct.tenant.api)
    implementation(libs.micrometer.core)

    testFixturesApi(libs.graphql.java)
    testFixturesApi(libs.kotest.property.jvm)
    testFixturesApi(libs.kotlinx.coroutines.core.jvm)
    testFixturesApi(libs.viaduct.engine.api)
    testFixturesApi(libs.viaduct.engine.runtime)
    testFixturesApi(libs.viaduct.service.api)
    testFixturesApi(libs.viaduct.shared.arbitrary)

    testFixturesImplementation(libs.caffeine)
    testFixturesImplementation(libs.checker.qual)
    testFixturesImplementation(libs.graphql.java.extension)
    testFixturesImplementation(libs.io.mockk.dsl)
    testFixturesImplementation(libs.io.mockk.jvm)
    testFixturesImplementation(libs.kotlinx.coroutines.jdk8)
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))

    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jspecify)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.strikt.core)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.engine.runtime))
    testImplementation(testFixtures(libs.viaduct.service.api))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))

    jmh(libs.jmh.annotation.processor)

    jmhAnnotationProcessor(libs.jmh.annotation.processor)

    jmhApi(libs.jmh.core)
    jmhApi(libs.viaduct.shared.arbitrary)

    jmhImplementation(libs.graphql.java)
    jmhImplementation(libs.kotest.property.jvm)
    jmhImplementation(libs.kotlinx.coroutines.core.jvm)
    jmhImplementation(libs.kotlinx.coroutines.jdk8)
    jmhImplementation(libs.viaduct.engine.runtime)
    jmhImplementation(testFixtures(libs.viaduct.engine.runtime))
}
