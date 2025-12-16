package detekt

import io.gitlab.arturbosch.detekt.Detekt
import viaduct.gradle.internal.repoRoot

val detektPluginsCfg = configurations.maybeCreate("detektPlugins")
val selfJar = files(javaClass.protectionDomain.codeSource.location)
dependencies { add(detektPluginsCfg.name, selfJar) }

tasks.register<Detekt>("detektCustomRules") {
    description = "Detekt for Custom Rules"
    group = "verification"
    val detektConfigFile = providers.provider { repoRoot().file("detekt.yml") }
    val detektViaductConfigFile = providers.provider { repoRoot().file("detekt-viaduct.yml") }

    setSource(files(repoRoot()))
    include("**/*.gradle.kts", "**/*.kt")
    exclude("**/demoapps/**","**/build/**", "**/.gradle/**", "**/buildSrc/**", "**/viaduct-bom/**")

    config.setFrom(detektConfigFile, detektViaductConfigFile)
    pluginClasspath.setFrom(detektPluginsCfg)

    ignoreFailures = false

    reports {
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt-custom-rules.html"))
        txt.required.set(true)
        txt.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt-custom-rules.txt"))
    }
}

plugins.withId("base") {
    tasks.named("check").configure { dependsOn("detektCustomRules") }
}
