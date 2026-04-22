package conventions

import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import viaduct.gradle.internal.repoRoot

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("detekt.viaduct-detekt-rules")
}

val detektConfigFile = providers.provider { repoRoot().file("detekt.yml") }

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

detekt {
    source.setFrom("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin", "src/jmh/kotlin")
    config.setFrom(detektConfigFile)
    ignoreFailures = true
}

ktlint {
    version.set(libs.findVersion("ktlintVersion").get().requiredVersion)
    enableExperimentalRules.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(true)

    filter {
        exclude { element ->
            element.file.path.contains("/generated-sources/") ||
                    element.file.path.contains("/build/generated/") ||
                    element.file.name.contains("SchemaObjects")
        }
    }
}

val detektCleanupConfigFile = providers.provider { repoRoot().file("detekt-cleanup.yml") }

tasks.register<Detekt>("findWarningsForCleanup") {
    description = "Finds unused members, unnecessary safe calls, name shadowing, and redundant suspend modifiers"
    group = "verification"

    source = files("src/main/kotlin", "src/test/kotlin", "src/testFixtures/kotlin", "src/jmh/kotlin")
        .asFileTree.matching {
            exclude("**/generated-sources/**", "**/build/generated/**")
        }
    config.setFrom(detektCleanupConfigFile)

    classpath.from(
        configurations.named("compileClasspath"),
        tasks.named<KotlinCompile>("compileKotlin").map { it.destinationDirectory }
    )
    jvmTarget = "17"

    ignoreFailures = false

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/detekt/find-warnings-for-cleanup.html"))
        txt.required.set(true)
        txt.outputLocation.set(layout.buildDirectory.file("reports/detekt/find-warnings-for-cleanup.txt"))
    }
}
