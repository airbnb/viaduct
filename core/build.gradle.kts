plugins {
    id("buildroot.orchestration")
    id("buildroot.versioning")
    id("conventions.bcv-module")
    jacoco
    `jacoco-report-aggregation`
}

// Jacoco configuration
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// Dependencies for jacoco aggregation - keep this copy in core for direct Buildkite use
// while the root copy remains in place for OSS/GitHub workflows.
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
    jacocoAggregation(libs.viaduct.shared.codegen)
    jacocoAggregation(libs.viaduct.shared.mapping)
    jacocoAggregation(libs.viaduct.shared.utils)
    jacocoAggregation(libs.viaduct.shared.viaductschema)
    jacocoAggregation(libs.viaduct.snipped.errors)
    jacocoAggregation(libs.viaduct.tenant.api)
    jacocoAggregation(libs.viaduct.tenant.codegen)
    jacocoAggregation(libs.viaduct.tenant.runtime)
}

reporting {
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testType = TestSuiteType.UNIT_TEST
        }
    }
}

tasks.register("testAndCoverage") {
    description = "Runs tests and generates coverage reports"
    group = "verification"

    dependsOn("testCodeCoverageReport")

    val isGitHubActions = providers.environmentVariable("GITHUB_ACTIONS")
        .map { it.toBoolean() }
        .orElse(false)
    val runnerOs = providers.environmentVariable("RUNNER_OS")
        .orElse("unknown")
    val javaVersion = providers.systemProperty("java.version")
        .orElse("unknown")

    doLast {
        logger.lifecycle("=".repeat(80))
        logger.lifecycle("Coverage Reports Generated:")
        logger.lifecycle("=".repeat(80))
        logger.lifecycle("📊 Individual module XML: */build/reports/jacoco/test/jacocoTestReport.xml")
        logger.lifecycle("📊 Aggregated XML:        core/build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")
        logger.lifecycle("📊 Aggregated HTML:       core/build/reports/jacoco/testCodeCoverageReport/html/index.html")
        logger.lifecycle("=".repeat(80))

        if (isGitHubActions.get()) {
            logger.lifecycle("🚀 Running in GitHub Actions")
            logger.lifecycle("   OS: ${runnerOs.get()} | Java: ${javaVersion.get()}")
            logger.lifecycle("::notice title=Coverage Reports::Generated at core/build/reports/jacoco/testCodeCoverageReport/html/index.html")
        }
    }
}
