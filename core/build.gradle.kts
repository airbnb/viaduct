import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

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

subprojects {
    pluginManager.withPlugin("base") {
        extensions.configure<BasePluginExtension> {
            archivesName.convention(path.removePrefix(":").replace(":", "-"))
        }
    }
}

// Treat Kotlin compiler warnings as errors across core modules, matching the Bazel build's -Werror.
// :tenant:tutorials is excluded so tutorial/example code stays free of -Werror brittleness.
// Non-core builds (gradle-plugins, gradletestapps, x/remoteresolvers, demoapps)
// consume the same build-logic but are not affected — they are separate included builds.
subprojects {
    if (path == ":tenant:tutorials") return@subprojects
    plugins.withId("org.jetbrains.kotlin.jvm") {
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions {
                allWarningsAsErrors = true
            }
        }
    }
}
