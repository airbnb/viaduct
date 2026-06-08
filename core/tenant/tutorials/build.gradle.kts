plugins {
    id("conventions.kotlin")
    id("feature-app-contracts")
    id("feature-app-contract-tests")
    id("conventions.kotlin-static-analysis")
    `java-test-fixtures`
}

viaductFeatureAppContracts {
    kotlin {
        contractsFrom(":tenant:tutorials")
    }
}

dependencies {
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.runtime))
    testFixturesImplementation(libs.viaduct.tenant.runtime)

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
