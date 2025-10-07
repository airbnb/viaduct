package conventions

plugins {
    jacoco
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().toString()
}

tasks.withType<Test>().configureEach {
    // Recommended JaCoCo settings when running on JDK 17+
    extensions.configure(JacocoTaskExtension::class) {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

tasks.named<JacocoReport>("jacocoTestReport") {
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

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.00".toBigDecimal() // Start with 0% and gradually increase
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.00".toBigDecimal() // Start with 0% and gradually increase
            }
        }
    }
}
