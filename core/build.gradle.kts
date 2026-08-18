import conventions.JacocoCoverageDataKind
import conventions.jacocoCoverageDataIncoming

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

val jacocoExecutionData = jacocoCoverageDataIncoming("ExecutionData", JacocoCoverageDataKind.EXECUTION_DATA, "jacocoExecutionDataElements")
val jacocoClassDirectories = jacocoCoverageDataIncoming("ClassDirectories", JacocoCoverageDataKind.CLASS_DIRECTORIES, "jacocoClassDirectoriesElements")
val jacocoSourceDirectories = jacocoCoverageDataIncoming("SourceDirectories", JacocoCoverageDataKind.SOURCE_DIRECTORIES, "jacocoSourceDirectoriesElements")

tasks.register<JacocoCoverageVerification>("testCodeCoverageVerification") {
    group = "verification"
    description = "Verifies coverage thresholds across all core subprojects"

    executionData.from(jacocoExecutionData)
    classDirectories.from(jacocoClassDirectories)
    sourceDirectories.from(jacocoSourceDirectories)

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
