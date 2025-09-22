plugins {
    id("buildroot.dependency-analysis")
    id("buildroot.orchestration")
    id("buildroot.versioning")
    jacoco
    `jacoco-report-aggregation`
}


orchestration {
    participatingIncludedBuilds.set(
        listOf("core", "codegen", "gradle-plugins")
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
    jacocoAggregation(libs.viaduct.shared.utils)
    jacocoAggregation(libs.viaduct.shared.viaductschema)
    jacocoAggregation(libs.viaduct.snipped.errors)
    jacocoAggregation(libs.viaduct.tenant.api)
    jacocoAggregation(libs.viaduct.tenant.codegen)
    jacocoAggregation(libs.viaduct.tenant.runtime)
//    jacocoAggregation(project(":tenant:testapps:policycheck"))
//    jacocoAggregation(project(":tenant:testapps:resolver"))
//    jacocoAggregation(project(":tenant:testapps:schemaregistration"))
}

// Configure the coverage report in the reporting block
reporting {
    reports {
        val testCodeCoverageReport by creating(JacocoCoverageReport::class) {
            testSuiteName = "test"
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
    description = "Runs tests and generates coverage reports for CircleCI"
    group = "verification"

    dependsOn("testCodeCoverageReport")

    doLast {
        println("Coverage reports generated:")
        println("- Individual module XML reports: */build/reports/jacoco/test/jacocoTestReport.xml")
        println("- Aggregated XML report: build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml")
        println("- Aggregated HTML report: build/reports/jacoco/testCodeCoverageReport/html/index.html")
    }
}
