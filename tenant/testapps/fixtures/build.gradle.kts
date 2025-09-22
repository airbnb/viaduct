plugins {
    id("conventions.kotlin-without-tests")
}

dependencies {
    api(libs.graphql.java)
    api(libs.junit)
    api(libs.viaduct.service.runtime)
    api(libs.viaduct.service.wiring)
    api(libs.viaduct.tenant.api)
    api(libs.viaduct.tenant.runtime)

    implementation(testFixtures(libs.viaduct.service.api))
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.service.api)
    implementation(libs.viaduct.snipped.errors)
    implementation(libs.guice)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.kotlinx.coroutines.jdk8)
}
