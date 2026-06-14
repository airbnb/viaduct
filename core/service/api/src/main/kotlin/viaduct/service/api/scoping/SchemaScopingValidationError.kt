package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * A single schema-scoping DSL validation failure: a stable [code] from [ScopingErrorCodes] paired
 * with a remediation-bearing [message].
 *
 * Mirrors `SchemaValidationError(code, message, location)` from the SDL-level validator, minus the
 * `location` field — DSL setters have no schema-location equivalent, and Gradle's own stack trace
 * already points at the offending `build.gradle.kts` line.
 *
 * Produced only by [SchemaScopingValidator.validate]'s cross-property pass, where multiple failures
 * are accumulated and rendered into a single aggregating `GradleException`. Per-ID setter checks
 * surface the same `[code] message` shape but throw directly rather than accumulating.
 */
@ExperimentalApi
data class SchemaScopingValidationError(
    val code: String,
    val message: String,
)
