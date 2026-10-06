package conventions

import buildroot.registerForOrchestrationAggregate

plugins {
    id("conventions.kotlin-compile-config")
    id("conventions.kotlin-without-tests")
    id("conventions.jacoco")
    id("conventions.test-retry")
    id("conventions.test-jvm")
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

dependencies {
    testImplementation(libs.findLibrary("junit").get())
    testImplementation(libs.findLibrary("junit-params").get())

    testRuntimeOnly(libs.findLibrary("junit-engine").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())

    constraints {
        testImplementation(libs.findLibrary("byte-buddy").get())
        testImplementation(libs.findLibrary("byte-buddy-agent").get())
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

registerForOrchestrationAggregate("test", "test")
registerForOrchestrationAggregate("testClasses", "testClasses")
