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
