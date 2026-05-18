plugins {
    kotlin("jvm")
    jacoco
    alias(libs.plugins.detekt)
}

group = "com.airbnb.viaduct"

detekt {
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    config.setFrom(layout.projectDirectory.dir("../..").file("detekt.yml"))
    ignoreFailures = true
}

dependencies {
    compileOnly(libs.detekt.api)
    compileOnly(libs.ktlint.rule.engine.core)
    compileOnly(libs.ktlint.cli.ruleset.core)
    testImplementation(libs.detekt.api)
    testImplementation(libs.detekt.test)
    testImplementation(libs.ktlint.rule.engine.core)
    testImplementation(libs.ktlint.cli.ruleset.core)
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    reports {
        xml.required = true
        xml.outputLocation = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/test/html")
        csv.required = false
    }
}
