plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

dependencies {
    implementation(libs.graphql.java)
    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.utils)

    implementation(libs.viaduct.service.api)

    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.viaduct.tenant.runtime)


    testImplementation(libs.guice)
    testFixturesImplementation(libs.viaduct.engine.api)
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))
}
