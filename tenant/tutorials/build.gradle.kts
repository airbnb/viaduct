plugins {
    id("conventions.kotlin")
    id("test-feature-app")
    id("conventions.kotlin-static-analysis")
}

viaductFeatureApp {}

dependencies {
    testImplementation(libs.graphql.java)
    testImplementation(libs.guice)
    testImplementation(libs.javax.inject)

    testImplementation(libs.viaduct.engine.api)
    testImplementation(libs.viaduct.service.api)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))
}
