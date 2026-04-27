package viaduct.gradle

import org.gradle.api.Project
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification

fun Project.resetCoverageThresholds(
    instructionMinimum: String? = null,
    branchMinimum: String? = null
) {
    tasks.named("jacocoTestCoverageVerification", JacocoCoverageVerification::class.java) {
        violationRules {
            rules.forEach { rule ->
                rule.limits.forEach { limit ->
                    when (limit.counter) {
                        "INSTRUCTION" -> instructionMinimum?.let { limit.minimum = it.toBigDecimal() }
                        "BRANCH" -> branchMinimum?.let { limit.minimum = it.toBigDecimal() }
                    }
                }
            }
        }
    }
}
