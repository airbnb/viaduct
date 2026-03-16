package conventions

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

tasks.withType<KotlinCompile>().configureEach {
    val taskName = name.lowercase()

    // Skip testFixtures compilations — those test the public API surface and should
    // not get internal opt-ins automatically.
    if (!taskName.contains("testfixtures")) {
        compilerOptions {
            optIn.add("viaduct.apiannotations.ExperimentalApi")
            optIn.add("viaduct.apiannotations.InternalApi")
            optIn.add("viaduct.apiannotations.VisibleForTest")
        }
    }
}
