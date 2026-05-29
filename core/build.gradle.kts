plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-module")
    jacoco
}

// Jacoco configuration
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.register<JacocoCoverageVerification>("testCodeCoverageVerification") {
    group = "verification"
    description = "Verifies coverage thresholds across all core subprojects"

    subprojects.forEach { sp ->
        sp.pluginManager.withPlugin("conventions.jacoco") {
            val reportTask = sp.tasks.named<JacocoReport>("jacocoTestReport")
            dependsOn(reportTask)
            executionData.from(reportTask.map { it.executionData })
            classDirectories.from(reportTask.map { it.classDirectories })
            sourceDirectories.from(reportTask.map { it.sourceDirectories })
        }
    }

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.10".toBigDecimal()
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.05".toBigDecimal()
            }
        }
    }
}
