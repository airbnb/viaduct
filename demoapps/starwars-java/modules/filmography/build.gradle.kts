plugins {
    `java-library`
    alias(libs.plugins.viaduct.module.java)
}

dependencies {
    api(libs.viaduct.javaapi.api)

    implementation(project(":common"))
    implementation(libs.micronaut.inject)
    annotationProcessor(libs.micronaut.inject.java)

    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.viaduct.javaapi.test.fixtures)
    testRuntimeOnly(libs.kotlin.reflect)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
