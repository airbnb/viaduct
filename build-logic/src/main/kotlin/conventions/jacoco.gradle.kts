package conventions

plugins {
    jacoco
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

jacoco {
    toolVersion = libs.findVersion("jacoco").get().toString()
}

tasks.named<Test>("test") {
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
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