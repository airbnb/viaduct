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
    contractsFrom(":tenant:tutorials")
}

val testFileBasedBootstrap by tasks.registering(Test::class) {
    description = "Runs all contract tests using the file-based bootstrap"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("feature-app-contract-test")
    }
    environment("USE_FILE_BASED_BOOTSTRAP", "true")
}

tasks.named("check") {
    dependsOn(testFileBasedBootstrap)
}

dependencies {
    viaductCodegenClasspath(libs.viaduct.tenant.codegen)

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
