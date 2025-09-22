package conventions

import viaduct.gradle.internal.repoRoot

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
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
