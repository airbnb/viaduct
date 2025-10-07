plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

dependencies {
    api(libs.graphql.java)
    api(libs.viaduct.tenant.api)

    implementation(libs.slf4j.api)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.core.jvm)
    implementation(libs.viaduct.tenant.runtime)

    testFixturesImplementation(libs.viaduct.engine.api)
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))
}
