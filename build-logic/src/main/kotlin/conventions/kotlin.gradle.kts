package conventions

plugins {
    id("conventions.kotlin-compile-config")
    id("conventions.kotlin-without-tests")
    id("conventions.jacoco")
    id("conventions.test-retry")
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

dependencies {
    testImplementation(libs.findLibrary("junit").get())
    testImplementation(libs.findLibrary("junit-params").get())

    testRuntimeOnly(libs.findLibrary("junit-engine").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
