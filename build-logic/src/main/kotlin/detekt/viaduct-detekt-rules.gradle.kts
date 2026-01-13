package detekt

import io.gitlab.arturbosch.detekt.Detekt
import viaduct.gradle.internal.repoRoot

// Only register the detektCustomRules task once across all subprojects
// Use a root project extra property to track if already registered
val taskRegisteredKey = "detektCustomRulesRegistered"
val isFirstRegistration = !rootProject.extra.has(taskRegisteredKey)
if (isFirstRegistration) {
    rootProject.extra.set(taskRegisteredKey, true)
}

val detektPluginsCfg = configurations.maybeCreate("detektPlugins")
val selfJar = files(javaClass.protectionDomain.codeSource.location)
dependencies { add(detektPluginsCfg.name, selfJar) }

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
