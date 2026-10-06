plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    api(libs.viaduct.shared.apiannotations)
    api(libs.viaduct.service.api)
    implementation(libs.viaduct.engine.api)
    implementation(libs.graphql.java)
    implementation(libs.viaduct.errors)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(libs.kotlinx.coroutines.test)
}
