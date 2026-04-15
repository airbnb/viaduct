plugins {
    `java-library`
    id("conventions.java")
    id("conventions.dokka")
    id("conventions.kotlin")
    `java-test-fixtures`
}

description = "Java Tenant API interfaces"

dependencies {
    api(libs.viaduct.engine.api)

    compileOnly(libs.jspecify)
    compileOnly(libs.spotbugs.annotations)

    /** Test fixtures - Viaduct dependencies **/
    testFixturesImplementation(testFixtures(libs.viaduct.engine.api))
    testFixturesImplementation(testFixtures(libs.viaduct.service.api))
    testFixturesImplementation(libs.viaduct.service.runtime)
    testFixturesImplementation(testFixtures(libs.viaduct.tenant.api))
    testFixturesImplementation(libs.viaduct.javaapi.runtime)

    testImplementation(libs.assertj.core)
}
