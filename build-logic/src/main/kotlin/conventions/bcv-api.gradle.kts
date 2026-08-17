package conventions

// This convention plugin adds binary compatibility validator library to any module that needs it :
// Use this plugin in api projects
//
// From binary-compatibility-validator we are using the tasks :
//
// - apiDump : runs apiDump on all subprojects that define an apiDump task
// - apiCheck: runs apiCheck on all subprojects that define an apiCheck task
//

import buildroot.registerForOrchestrationAggregate
import kotlinx.validation.ApiValidationExtension
import io.gitlab.arturbosch.detekt.Detekt
import viaduct.gradle.internal.repoRoot

plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

// Self-report to the orchestration registry instead of having a root project's
// `subprojects { }` block read this project's task container.
registerForOrchestrationAggregate("apiDump", "apiDump")
registerForOrchestrationAggregate("apiCheck", "apiCheck")

configure<ApiValidationExtension> {
    publicMarkers.add("viaduct.apiannotations.StableApi")
    nonPublicMarkers.add("viaduct.apiannotations.ExperimentalApi")
    nonPublicMarkers.add("viaduct.apiannotations.InternalApi")
    nonPublicMarkers.add("viaduct.apiannotations.TypeInferenceApi")
    nonPublicMarkers.add("viaduct.apiannotations.VisibleForTest")
}

pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
    tasks.withType(Detekt::class.java).configureEach {
        config.from(files(repoRoot().file("detekt-viaduct-bcv.yml")))
    }
}
