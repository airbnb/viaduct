package conventions

import io.gitlab.arturbosch.detekt.Detekt
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import viaduct.gradle.internal.repoRoot

plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
    id("detekt.viaduct-detekt-rules")
}

val detektConfigFile = providers.provider { repoRoot().file("detekt.yml") }
val detektViaductConfigFile = providers.provider { repoRoot().file("detekt-viaduct.yml") }
val handWrittenKotlinSourceDirs = mapOf(
    "MainSourceSet" to "src/main/kotlin",
    "TestSourceSet" to "src/test/kotlin",
    "TestFixturesSourceSet" to "src/testFixtures/kotlin",
    "JmhSourceSet" to "src/jmh/kotlin"
)

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

detekt {
    source.setFrom(handWrittenKotlinSourceDirs.values)
    config.setFrom(detektConfigFile, detektViaductConfigFile)
}

ktlint {
    version.set(libs.findVersion("ktlintVersion").get().requiredVersion)
    enableExperimentalRules.set(true)
    outputToConsole.set(true)

    filter {
        exclude("**/build/**")
        exclude("**/generated-sources/**")
        exclude("**/generated-resources/**")
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

    baseline.set(repoRoot().file("detekt-cleanup-baseline.xml"))

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/detekt/find-warnings-for-cleanup.html"))
        txt.required.set(true)
        txt.outputLocation.set(layout.buildDirectory.file("reports/detekt/find-warnings-for-cleanup.txt"))
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/find-warnings-for-cleanup.xml"))
    }
}

tasks.withType<BaseKtLintCheckTask>().configureEach {
    handWrittenKotlinSourceDirs.entries.firstOrNull { (suffix, _) -> name.endsWith(suffix) }
        ?.let { (_, sourceDir) -> setSource(sourceDir) }
}

dependencies {
    add("ktlintRuleset", "com.airbnb.viaduct:build-ktlint")
}
