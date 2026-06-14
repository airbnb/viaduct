package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * Pure validation logic for the application's schema-scoping declarations.
 *
 * Deliberately Gradle-free: this lives in the runtime `service/api` module so the build-to-runtime
 * scoping contract has a single home, so it cannot throw `org.gradle.api.GradleException`. Instead
 * it owns the regex constants, reserved-ID set, error codes, and remediation messages, and reports
 * failures as data. The `viaductApplication` Gradle plugin renders that data into `[code] message`
 * `GradleException`s.
 *
 * Validation uses **hybrid timing**:
 * - [validateScopeId] / [validateSchemaId] inspect a single ID and are called at setter time, so a
 *   typo like `setOf("BAD-ID")` fails with a stack trace at the offending `build.gradle.kts` line.
 * - [validate] inspects cross-property invariants (subset, universe presence) and is called once
 *   after both declarations settle, so it does not depend on declaration order and can batch every
 *   violation into one message.
 */
@ExperimentalApi
object SchemaScopingValidator {
    // The two ID kinds live in different contexts, so they get different patterns:

    /** Scope IDs appear in `@scope(to: [...])`, so they follow GraphQL identifier conventions. */
    const val SCOPE_ID_PATTERN = "^[a-z][a-z0-9_]*$"

    /** Scoped-schema IDs are API names callers type (e.g. `PUBLIC_API`, `publicApi`, `FullApi`). */
    const val SCHEMA_ID_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$"

    /**
     * Scoped-schema IDs reserved by Viaduct and rejected if declared.
     *
     * `FULL` and `NONE` are kept free as runtime sentinels for the manifest reader / dual-API
     * `SchemaConfiguration` factories (Series B, slices 8–9): `FULL` names the unfiltered schema and
     * `NONE` the empty selection. Reserving them at declaration time stops an application from
     * shadowing those sentinels with a concrete scoped schema.
     */
    val RESERVED_SCHEMA_IDS = setOf("FULL", "NONE")

    private val scopeIdRegex = Regex(SCOPE_ID_PATTERN)
    private val schemaIdRegex = Regex(SCHEMA_ID_PATTERN)

    /**
     * Validates a single scope ID's syntax. Returns `null` when [id] is well-formed, or the error
     * to surface otherwise. The caller (a DSL setter) throws directly on a non-null result.
     */
    fun validateScopeId(id: String): SchemaScopingValidationError? =
        if (scopeIdRegex.matches(id)) {
            null
        } else {
            SchemaScopingValidationError(
                code = ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID,
                message = "Scope id '$id' does not match $SCOPE_ID_PATTERN. " +
                    "Scope ids appear in @scope(to: [...]) and follow GraphQL identifier conventions.",
            )
        }

    /**
     * Validates a single scoped-schema ID: first against the reserved-name set, then against the
     * syntax pattern. Returns `null` when [id] is acceptable, or the error to surface otherwise.
     */
    fun validateSchemaId(id: String): SchemaScopingValidationError? =
        when {
            id in RESERVED_SCHEMA_IDS -> SchemaScopingValidationError(
                code = ScopingErrorCodes.SCHEMA_ID_RESERVED,
                message = "Scoped-schema id '$id' is reserved by Viaduct and cannot be declared.",
            )
            schemaIdRegex.matches(id) -> null
            else -> SchemaScopingValidationError(
                code = ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID,
                message = "Scoped-schema id '$id' does not match $SCHEMA_ID_PATTERN. " +
                    "Examples: PUBLIC_API, publicApi, FullApi.",
            )
        }

    /**
     * Validates cross-property invariants over a settled [scoping] snapshot and returns every
     * violation found (empty when valid). Per-ID syntax is assumed already enforced at setter time.
     *
     * Checks:
     * - **Subset:** each scoped schema's scope set must be a subset of the declared universe.
     * - **Universe presence:** declaring any scoped schema without a universe is rejected, including
     *   the full-schema alias form `"X" to emptySet()` — scoped schemas imply scoping, and the
     *   runtime manifest contract assumes a declared universe.
     *
     * A universe with no scoped schemas is intentionally valid: extra unused scopes are fine.
     */
    fun validate(scoping: SchemaScoping): List<SchemaScopingValidationError> {
        val errors = mutableListOf<SchemaScopingValidationError>()

        scoping.scopedSchemas.forEach { (id, scopes) ->
            val unknown = scopes - scoping.scopeUniverse
            if (unknown.isNotEmpty()) {
                errors += SchemaScopingValidationError(
                    code = ScopingErrorCodes.SCOPE_SET_NOT_SUBSET,
                    message = "Scoped schema '$id' references scopes not declared in declaredSchemaScopes: " +
                        "${unknown.sorted()}. Declared scopes: ${scoping.scopeUniverse.sorted()}.",
                )
            }
        }

        if (!scoping.isScoped && scoping.scopedSchemas.isNotEmpty()) {
            errors += SchemaScopingValidationError(
                code = ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE,
                message = "declaredScopedSchemas is set but no scope universe is declared. " +
                    "Call declaredSchemaScopes(...) with at least one scope, or remove the scoped schemas.",
            )
        }

        return errors
    }
}
