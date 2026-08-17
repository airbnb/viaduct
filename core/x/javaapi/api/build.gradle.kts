import viaduct.gradle.resetCoverageThresholds

plugins {
    `java-library`
    id("conventions.java")
    id("conventions.dokka")
    id("conventions.kotlin")
    `java-test-fixtures`
}

resetCoverageThresholds(instructionMinimum = "0.10", branchMinimum = "0.10")

description = "Java Tenant API interfaces"

dependencies {
    api(libs.viaduct.engine.api)
    // Exposed transitively so generated Arguments GRTs can reference @InternalApi.
    api(libs.viaduct.shared.apiannotations)
    // Shared connection pagination math (OffsetCursorCodec, ConnectionArgumentsSupport).
    implementation(libs.viaduct.tenant.shared)
    implementation(libs.viaduct.shared.graphql)
    implementation(libs.viaduct.shared.viaductschema)

    compileOnly(libs.jspecify)

    testImplementation(libs.assertj.core)

    /** Test fixtures - Viaduct dependencies **/
    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(libs.viaduct.service.runtime)
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.runtime))
    testFixturesImplementation(libs.viaduct.javaapi.runtime)
    // BootstrapperFactory (file-based bootstrap entry point) lives in engine:wiring.
    testFixturesImplementation(libs.viaduct.engine.wiring)

}
