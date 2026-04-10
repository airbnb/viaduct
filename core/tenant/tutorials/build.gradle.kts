plugins {
    id("conventions.kotlin")
    id("test-feature-app")
    id("feature-app-contracts")
    id("feature-app-contract-tests")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

viaductFeatureApp {}

viaductFeatureAppContracts {
    contractsFrom(":tenant:tenant-tutorials")
}

dependencies {
    viaductCodegenClasspath(libs.viaduct.tenant.codegen)

    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.runtime))

    testImplementation(libs.graphql.java)
    testImplementation(libs.guice)
    testImplementation(libs.javax.inject)

    testImplementation(libs.viaduct.shared.apiannotations)
    testImplementation(libs.viaduct.engine.api)
    testImplementation(libs.viaduct.service.api)
    testImplementation(libs.viaduct.service.runtime)
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.tenant.runtime))
    testImplementation(testFixtures(project))
}
