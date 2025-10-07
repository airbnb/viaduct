plugins {
    id("buildroot.dependency-analysis")
    id("buildroot.orchestration")
    id("buildroot.versioning")
    jacoco
    `jacoco-report-aggregation`
}


orchestration {
    participatingIncludedBuilds.set(
        listOf("core", "gradle-plugins")
    )
}

// Jacoco configuration
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// Dependencies for jacoco aggregation - all Java subprojects with jacoco
dependencies {
    jacocoAggregation(libs.viaduct.engine.api)
    jacocoAggregation(libs.viaduct.engine.runtime)
    jacocoAggregation(libs.viaduct.service.api)
    jacocoAggregation(libs.viaduct.service.runtime)
    jacocoAggregation(libs.viaduct.shared.arbitrary)
    jacocoAggregation(libs.viaduct.shared.dataloader)
    jacocoAggregation(libs.viaduct.shared.deferred)
    jacocoAggregation(libs.viaduct.shared.graphql)
    jacocoAggregation(libs.viaduct.shared.invariants)
    jacocoAggregation(libs.viaduct.shared.logging)
    jacocoAggregation(libs.viaduct.shared.codegen)
    jacocoAggregation(libs.viaduct.shared.mapping)
    jacocoAggregation(libs.viaduct.shared.utils)
    jacocoAggregation(libs.viaduct.shared.viaductschema)
    jacocoAggregation(libs.viaduct.snipped.errors)
    jacocoAggregation(libs.viaduct.tenant.api)
    jacocoAggregation(libs.viaduct.tenant.codegen)
    jacocoAggregation(libs.viaduct.tenant.runtime)
}

// Configure the coverage report in the reporting block
reporting {
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName = "test"
        }
    }
}

// Task to collect JUnit XML test results for CircleCI
tasks.register<Copy>("collectTestResults") {
    description = "Collects JUnit XML test results from all modules for CircleCI"
    group = "verification"

    // Make sure tests have run first
    mustRunAfter("test")

    from(fileTree(".") {
        include("**/build/test-results/test/*.xml")
    })
    into("build/test-results-for-circleci")

    // Rename files to avoid conflicts
    rename { filename ->
        val sourceFile = source.find { it.name == filename }
        if (sourceFile != null) {
            val relativePath = rootDir.toPath().relativize(sourceFile.toPath())
            relativePath.toString()
                .replace("/build/test-results/test/", "_")
                .replace("/", "_")
                .replace("\\", "_")
        } else {
            filename
        }
    }

    doLast {
        val outputDir = File(rootDir, "build/test-results-for-circleci")
        var totalTests = 0
        var totalFiles = 0

        outputDir.listFiles { file -> file.name.endsWith(".xml") }?.forEach { xmlFile ->
            totalFiles++

            // Count tests in this file
            val content = xmlFile.readText()
            val testsMatch = Regex("""tests="(\d+)"""").find(content)
            if (testsMatch != null) {
                totalTests += testsMatch.groupValues[1].toInt()
            }
        }

        println("✅ Test results collected for CircleCI:")
        println("   - Total XML files: $totalFiles")
        println("   - Total tests: $totalTests")
        println("   - Output directory: ${outputDir.absolutePath}")

        if (totalFiles == 0) {
            println("⚠️  Warning: No test result files found. Make sure tests have been run first.")
        }
    }
}

// Coverage verification with reasonable thresholds
tasks.register<JacocoCoverageVerification>("testCodeCoverageVerification") {
    dependsOn("testCodeCoverageReport")

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                value = "COVEREDRATIO"
                minimum = "0.10".toBigDecimal() // 10% minimum instruction coverage
            }
        }
        rule {
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.05".toBigDecimal() // 5% minimum branch coverage
            }
        }
    }
}

// CircleCI-friendly task to run tests and generate coverage
tasks.register("testAndCoverage") {
    description = "Runs tests and generates coverage reports and collects test results for CircleCI"
    group = "verification"

    dependsOn("testCodeCoverageReport", "collectTestResults")

    doLast {
        println("Coverage reports generated:")
        println("- Individual module XML reports: */build/reports/jacoco/test/jacocoTestReport.xml")
        println("- Aggregated XML report: build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")
        println("- Aggregated HTML report: build/reports/jacoco/testCodeCoverageReport/html/index.html")
        println()
        println("Test results collected:")
        println("- CircleCI JUnit XML files: build/test-results-for-circleci/")
    }
}
