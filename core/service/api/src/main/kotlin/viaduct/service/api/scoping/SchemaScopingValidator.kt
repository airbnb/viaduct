package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * One validation finding produced by [SchemaScopingValidator]. Pure value object — callers (the
 * Gradle extension or plugin) translate it into a `GradleException` rendered as `[code] message`.
 */
@ExperimentalApi
data class SchemaScopingValidationError(
    val code: String,
    val message: String,
)

/**
 * Pure validation logic for the schema-scoping DSL declarations. Lives in the same package as
 * [SchemaScoping] so configuration-time and runtime consumers share the same rules. Has no
 * dependency on the Gradle API — callers are responsible for throwing the build-tool exception.
 *
 * The validator splits checks into two buckets that match the DSL's hybrid timing:
 *
 * - **Per-ID syntax** ([validateScopeId], [validateSchemaId]) fires synchronously inside DSL
 *   setters. A failure points at the offending line in `build.gradle.kts`.
 * - **Cross-property invariants** ([validate]) fires in `afterEvaluate` after both DSL properties
 *   have settled, so the rules do not depend on the order of DSL calls.
 */
@ExperimentalApi
object SchemaScopingValidator {
    /** SDL identifier shape — matches scope names that appear in `@scope(to: [...])`. */
    const val SCOPE_ID_PATTERN = "^[a-z][a-z0-9_]*$"

    /** API-name shape — allows the `PUBLIC_API` / `publicApi` styles used for scoped-schema IDs. */
    const val SCHEMA_ID_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$"

    /** Scoped-schema IDs reserved by Viaduct for internal sentinels. */
    val RESERVED_SCHEMA_IDS: Set<String> = setOf("FULL", "NONE")

    private val scopeIdRegex = Regex(SCOPE_ID_PATTERN)
    private val schemaIdRegex = Regex(SCHEMA_ID_PATTERN)

    /**
     * Returns an error if [id] is not a valid scope ID, or `null` if it is.
     *
     * The SDL identifier shape intentionally rejects the `*` wildcard at the DSL layer; `*` is a
     * sentinel that may appear inside `@scope(to: [...])` in SDL but is not a value the DSL
     * accepts as a declared scope name.
     */
    fun validateScopeId(id: String): SchemaScopingValidationError? =
        if (scopeIdRegex.matches(id)) {
            null
        } else {
            SchemaScopingValidationError(
                code = ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID,
                message = "Scope id '$id' does not match required pattern $SCOPE_ID_PATTERN. " +
                    "Scope ids appear in @scope(to: [...]) and follow GraphQL identifier conventions: " +
                    "lowercase letters, digits, and underscores, starting with a letter.",
            )
        }

    /**
     * Returns an error if [id] is not a valid scoped-schema ID, or `null` if it is. Reserved IDs
     * are checked before the format regex so the user receives the more specific message.
     */
    fun validateSchemaId(id: String): SchemaScopingValidationError? =
        when {
            id in RESERVED_SCHEMA_IDS -> SchemaScopingValidationError(
                code = ScopingErrorCodes.SCHEMA_ID_RESERVED,
                message = "Scoped-schema id '$id' is reserved by Viaduct and cannot be declared. " +
                    "Reserved ids: ${RESERVED_SCHEMA_IDS.sorted()}.",
            )
            !schemaIdRegex.matches(id) -> SchemaScopingValidationError(
                code = ScopingErrorCodes.SCHEMA_ID_FORMAT_INVALID,
                message = "Scoped-schema id '$id' does not match required pattern $SCHEMA_ID_PATTERN. " +
                    "Examples: PUBLIC_API, publicApi, FullApi.",
            )
            else -> null
        }

    /**
     * Returns the list of cross-property violations in [scoping] (empty when valid). Intended to
     * run once at the end of configuration; the caller batches all findings into a single
     * `GradleException`.
     */
    fun validate(scoping: SchemaScoping): List<SchemaScopingValidationError> {
        val errors = mutableListOf<SchemaScopingValidationError>()

        if (!scoping.isScoped && scoping.scopedSchemas.isNotEmpty()) {
            errors += SchemaScopingValidationError(
                code = ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE,
                message = "declaredScopedSchemas declares ${scoping.scopedSchemas.size} entry/entries but " +
                    "declaredSchemaScopes was not called. Declare the scope universe via declaredSchemaScopes, " +
                    "or remove declaredScopedSchemas entirely.",
            )
        }

        // Subset checks only meaningful when a universe is declared; the no-universe case is
        // already covered above and a missing universe makes "unknown" trivially every reference.
        if (!scoping.isScoped) return errors

        scoping.scopedSchemas.toSortedMap().forEach { (id, scopes) ->
            val unknown = (scopes - scoping.scopeUniverse).sorted()
            if (unknown.isNotEmpty()) {
                errors += SchemaScopingValidationError(
                    code = ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE,
                    message = "Scoped schema '$id' references scope id(s) not in declaredSchemaScopes: " +
                        "$unknown. Declared scopes: ${scoping.scopeUniverse.sorted()}.",
                )
            }
        }
        return errors
    }
}
