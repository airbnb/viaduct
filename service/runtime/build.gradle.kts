plugins {
    id("conventions.kotlin")
}

viaductPublishing {
    name.set("Service Runtime")
    description.set("The main entrypoint for Viaduct at runtime.")
}

dependencies {
    implementation(libs.viaduct.service.api)
    implementation(libs.graphql.java)
    implementation(libs.guice)


    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.engine.wiring)

    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.caffeine)
    implementation(libs.classgraph)
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.micrometer.core)

    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.jspecify)
    testImplementation(libs.kotlinx.coroutines.test)
}
