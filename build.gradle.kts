import org.gradle.api.tasks.Copy

plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-root")
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
    jacocoAggregation(libs.viaduct.shared.apiannotations)
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
            testType = TestSuiteType.UNIT_TEST
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

// GitHub Actions-friendly task to run tests and generate coverage
tasks.register("testAndCoverage") {
    description = "Runs tests and generates coverage reports"
    group = "verification"

    dependsOn("testCodeCoverageReport")

    // Capture values at configuration time
    val isGitHubActions = providers.environmentVariable("GITHUB_ACTIONS")
        .map { it.toBoolean() }
        .orElse(false)
    val runnerOs = providers.environmentVariable("RUNNER_OS")
        .orElse("unknown")
    val javaVersion = providers.systemProperty("java.version")
        .orElse("unknown")

    doLast {
        logger.lifecycle("=" .repeat(80))
        logger.lifecycle("Coverage Reports Generated:")
        logger.lifecycle("=" .repeat(80))
        logger.lifecycle("📊 Individual module XML: */build/reports/jacoco/test/jacocoTestReport.xml")
        logger.lifecycle("📊 Aggregated XML:        build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")
        logger.lifecycle("📊 Aggregated HTML:       build/reports/jacoco/testCodeCoverageReport/html/index.html")
        logger.lifecycle("=" .repeat(80))

        if (isGitHubActions.get()) {
            logger.lifecycle("🚀 Running in GitHub Actions")
            logger.lifecycle("   OS: ${runnerOs.get()} | Java: ${javaVersion.get()}")
            logger.lifecycle("::notice title=Coverage Reports::Generated at build/reports/jacoco/testCodeCoverageReport/html/index.html")
        }
    }
}
