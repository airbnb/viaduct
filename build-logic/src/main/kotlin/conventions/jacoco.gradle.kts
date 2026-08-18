package conventions

import buildroot.registerProjectPathForOrchestrationAggregate

plugins {
    jacoco
}

// Self-report this project's own path so an aggregating project (see core/build.gradle.kts)
// knows which project paths to add as dependencies on the resolvable jacoco coverage
// configurations, instead of scanning subprojects{} and reading their applied plugins directly.
registerProjectPathForOrchestrationAggregate("jacocoCoverageData")

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().toString()
}

fun Configuration.jacocoCoverageDataOutgoing(kind: String) {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(jacocoCoverageDataKind, kind) }
}

val jacocoExecutionDataElements = configurations.create("jacocoExecutionDataElements") {
    description = "Consumable configuration for this project's jacocoTestReport execution data."
    jacocoCoverageDataOutgoing(JacocoCoverageDataKind.EXECUTION_DATA)
}
val jacocoClassDirectoriesElements = configurations.create("jacocoClassDirectoriesElements") {
    description = "Consumable configuration for this project's jacocoTestReport class directories."
    jacocoCoverageDataOutgoing(JacocoCoverageDataKind.CLASS_DIRECTORIES)
}
val jacocoSourceDirectoriesElements = configurations.create("jacocoSourceDirectoriesElements") {
    description = "Consumable configuration for this project's jacocoTestReport source directories."
    jacocoCoverageDataOutgoing(JacocoCoverageDataKind.SOURCE_DIRECTORIES)
}

tasks.withType<Test>().configureEach {
    // Recommended JaCoCo settings when running on JDK 17+
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val jacocoTestReport = tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))

    // Include testFixtures source set in coverage if the java-test-fixtures plugin is applied
    pluginManager.withPlugin("java-test-fixtures") {
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        val testFixtures = sourceSets.named("testFixtures")

        // sources for coverage report (testFixtures code)
        sourceDirectories.from(testFixtures.map { it.allSource.srcDirs })

        // compiled classes for coverage analysis
        classDirectories.from(testFixtures.map { it.output.classesDirs })

        // ensure classes exist before report runs
        dependsOn(testFixtures.map { it.classesTaskName }.flatMap { tasks.named(it) })
    }

    reports {
        xml.required = true
        xml.outputLocation = layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
        html.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/test/html")
        csv.required = false
    }
}

// Publish jacocoTestReport's own inputs as artifacts, so a consumer resolves this project's
// coverage data through the dependency graph instead of reading this project's task directly.
jacocoExecutionDataElements.outgoing.artifacts(
    jacocoTestReport.map { it.executionData.files }
)
jacocoClassDirectoriesElements.outgoing.artifacts(
    jacocoTestReport.map { it.classDirectories.files }
)
jacocoSourceDirectoriesElements.outgoing.artifacts(
    jacocoTestReport.map { it.sourceDirectories.files }
)


tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.75".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.50".toBigDecimal()
            }
        }
    }
}
