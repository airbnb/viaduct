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

tasks.named("publishToMavenLocal") {
    dependsOn(gradle.includedBuild("build-logic").task(":shared:publishToMavenLocal"))
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

// Runs tests, generates coverage reports, and verifies coverage thresholds in one shot
tasks.register("testWithCoverage") {
    description = "Runs tests, generates coverage reports, and verifies coverage thresholds"
    group = "verification"
    dependsOn("test", "testCodeCoverageReport", "testCodeCoverageVerification")
}

