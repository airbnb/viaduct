package conventions

// This convention plugin adds binary compatibility validator library to any module that needs it :
// Use this plugin in api projects
//
// From binary-compatibility-validator we are using the tasks :
//
// - apiDump : runs apiDump on all subprojects that define an apiDump task
// - apiCheck: runs apiCheck on all subprojects that define an apiCheck task
//

import kotlinx.validation.ApiValidationExtension

plugins {
    id("org.jetbrains.kotlinx.binary-compatibility-validator")
}

configure<ApiValidationExtension> {
    nonPublicMarkers.add("viaduct.InternalApi")
    nonPublicMarkers.add("viaduct.TestingApi")
    nonPublicMarkers.add("viaduct.ExperimentalApi")
}

// We need to control apiCheck execution
// this code removes apiCheck from check task
// apiCheck is executed independently in CI scripts after building the project
tasks.named("check").configure {
    val filteredDependsOn = dependsOn.filterNot { dep ->
        when (dep) {
            is TaskProvider<*> -> dep.name == "apiCheck"
            is Task -> dep.name == "apiCheck"
            else -> false
        }
    }

    dependsOn.clear()
    dependsOn.addAll(filteredDependsOn)
}
