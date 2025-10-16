plugins {
    id("conventions.kotlin")
}

viaductPublishing {
    name.set("Engine Wiring")
    description.set("The main entrypoint for the Viaduct engine.")
}

dependencies {

    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.shared.utils)

    implementation(libs.guice)
    implementation(libs.graphql.java)
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.classgraph)
    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.micrometer.core)

    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(testFixtures(libs.viaduct.engine.api))
}
