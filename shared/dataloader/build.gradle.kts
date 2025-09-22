plugins {
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
}

dependencies {
    api(libs.javax.inject)
    api(libs.guice)
    api(libs.viaduct.service.api)

    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.junit.params)
    testImplementation(libs.kotlinx.coroutines.debug)
    runtimeOnly(libs.kotlinx.coroutines.jdk8)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.viaduct.shared.utils)

    testCompileOnly(libs.kotlinx.coroutines.jdk8)

    testFixturesImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.viaduct.service.api)
}
