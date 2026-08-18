plugins {
    kotlin("jvm")
    jacoco
    id("build-logic.conventions")
    alias(libs.plugins.detekt)
}

group = "com.airbnb.viaduct"

detekt {
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    config.setFrom(layout.projectDirectory.dir("../..").file("detekt.yml"))
}

dependencies {
    compileOnly(libs.detekt.api)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}
