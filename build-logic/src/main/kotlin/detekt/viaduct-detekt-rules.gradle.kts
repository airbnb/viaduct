package detekt

import io.gitlab.arturbosch.detekt.Detekt
import viaduct.gradle.internal.repoRoot

// Only run custom detekt from the root build where build-logic:common exists.
// Included builds (like included-builds/core) don't have access to this project.
val isRootBuild = gradle.parent == null

// Only register the detektCustomRules task once across all subprojects
// Use a root project extra property to track if already registered
val taskRegisteredKey = "detektCustomRulesRegistered"
val isFirstRegistration = isRootBuild && !rootProject.extra.has(taskRegisteredKey)
if (isFirstRegistration) {
    rootProject.extra.set(taskRegisteredKey, true)
}

val detektPluginsCfg = configurations.maybeCreate("detektPlugins")
if (isRootBuild) {
    dependencies { add(detektPluginsCfg.name, "com.airbnb.viaduct:common") }
}

if (isFirstRegistration) {
    tasks.register<Detekt>("detektCustomRules") {
        description = "Detekt for Custom Rules"
        group = "verification"
        val detektConfigFile = providers.provider { repoRoot().file("detekt.yml") }
        val detektViaductConfigFile = providers.provider { repoRoot().file("detekt-viaduct.yml") }

        setSource(files(repoRoot()))
        include("**/*.gradle.kts", "**/*.kt")
        exclude("**/demoapps/**", "**/build/**", "**/.gradle/**", "**/buildSrc/**", "**/viaduct-bom/**")

        config.setFrom(detektConfigFile, detektViaductConfigFile)
        pluginClasspath.setFrom(detektPluginsCfg)

        ignoreFailures = false

        reports {
            html.required.set(true)
            html.outputLocation.set(rootProject.layout.buildDirectory.file("reports/detekt/detekt-custom-rules.html"))
            txt.required.set(true)
            txt.outputLocation.set(rootProject.layout.buildDirectory.file("reports/detekt/detekt-custom-rules.txt"))
        }
    }

    // Add check task dependency only from this project
    plugins.withId("base") {
        tasks.named("check").configure { dependsOn("detektCustomRules") }
    }
}
