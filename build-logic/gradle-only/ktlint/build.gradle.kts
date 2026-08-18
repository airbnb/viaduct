plugins {
    kotlin("jvm")
    jacoco
    id("build-logic.conventions")
    alias(libs.plugins.detekt)
}

group = "com.airbnb.viaduct"

detekt {
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    config.setFrom(layout.projectDirectory.dir("../../..").file("detekt.yml"))
}

dependencies {
    compileOnly(libs.ktlint.rule.engine.core)
    compileOnly(libs.ktlint.cli.ruleset.core)
    testImplementation(libs.ktlint.rule.engine.core)
    testImplementation(libs.ktlint.cli.ruleset.core)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}
