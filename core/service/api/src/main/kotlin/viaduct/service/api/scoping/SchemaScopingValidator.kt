package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * One validation finding produced by [SchemaScopingValidator] or [SchemaScopeDefinitions]. Pure value
 * object — callers translate it into their own build-tool failure, rendered as `[code] message`.
 */
@ExperimentalApi
data class SchemaScopingValidationError(
    val code: String,
    val message: String,
)

/**
 * Pure validation logic for schema scope definitions. Lives in the same package as [SchemaScoping] so
 * build-time and runtime consumers share the same rules. Has no dependency on any build tool — callers
 * are responsible for throwing their own exception.
 *
 * The validator splits checks into two buckets, and [SchemaScopeDefinitions] drives both:
 *
 * - **Per-ID syntax** ([validateScopeId], [validateSchemaId]) applies to one identifier at a time.
 * - **Cross-property invariants** ([validate]) apply to an assembled [SchemaScoping]: that named
 *   entries require a declared universe, and that their scope sets stay inside it. Nothing else.
 *   In particular an empty scope set passes here, because subtracting the universe from an empty set
 *   leaves nothing to report; [SchemaScopeDefinitions] rejects it.
 */
@ExperimentalApi
object SchemaScopingValidator {
    /**
     * Shape of a scope name as it appears in `@scope(to: [...])` — a string argument, not a GraphQL identifier.
     *
     * Admits the colon-namespaced, hyphenated IDs already in production: `viaduct:public`,
     * `listing-block:private`, and `viaduct:__generated-types`, which is why the segment after the colon
     * cannot require a leading letter.
     */
    const val SCOPE_ID_PATTERN = "^[A-Za-z][A-Za-z0-9_-]*(:[A-Za-z0-9_-]+)?$"

    /** API-name shape — allows the `PUBLIC_API` / `publicApi` styles used for scoped-schema IDs. */
    const val SCHEMA_ID_PATTERN = "^[A-Za-z][A-Za-z0-9_]*$"

    /** Reserved id of the unscoped schema, requested at runtime rather than declared. */
    const val BASE_SCHEMA_ID: String = "BASE"

    private const val PRIVATE_SCOPE_SUFFIX = ":private"

    /** Scoped-schema IDs reserved by Viaduct for internal sentinels. */
    val RESERVED_SCHEMA_IDS: Set<String> = setOf(BASE_SCHEMA_ID, "NONE")

    private val scopeIdRegex = Regex(SCOPE_ID_PATTERN)
    private val schemaIdRegex = Regex(SCHEMA_ID_PATTERN)

    /**
     * Returns an error if [id] is not a valid scope ID, or `null` if it is.
     *
     * The shape rejects the `*` wildcard: `*` may appear inside `@scope(to: [...])` in SDL to mean "all
     * scopes", but it is not itself a declarable scope.
     */
    fun validateScopeId(id: String): SchemaScopingValidationError? =
        if (scopeIdRegex.matches(id)) {
            null
        } else {
            SchemaScopingValidationError(
                code = ScopingErrorCodes.SCOPE_ID_FORMAT_INVALID,
                message = "Scope id '$id' does not match required pattern $SCOPE_ID_PATTERN. " +
                    "Scope ids appear in @scope(to: [...]): letters, digits, underscores and hyphens, " +
                    "starting with a letter, optionally with one ':'-separated namespace segment. " +
                    "Examples: viaduct:public, listing-block:private.",
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
     * Returns the list of cross-property violations in [scoping] (empty when valid). Runs once against
     * assembled definitions; the caller batches all findings into a single failure.
     */
    fun validate(scoping: SchemaScoping): List<SchemaScopingValidationError> {
        val errors = mutableListOf<SchemaScopingValidationError>()

        if (!scoping.isScoped && scoping.scopedSchemas.isNotEmpty()) {
            errors += SchemaScopingValidationError(
                code = ScopingErrorCodes.SCOPED_SCHEMAS_WITHOUT_UNIVERSE,
                message = "${scoping.scopedSchemas.size} scopedSchemas entry/entries are declared but " +
                    "schemaScopes is omitted. Declare the scope universe under schemaScopes, or remove the " +
                    "scopedSchemas entries entirely.",
            )
        }

        // Subset checks only meaningful when a universe is declared; the no-universe case is
        // already covered above and a missing universe makes "unknown" trivially every reference.
        if (!scoping.isScoped) return errors

        scoping.scopedSchemas.toSortedMap().forEach { (id, scopes) ->
            val unknown = (scopes - scoping.scopeUniverse).sorted()
            if (unknown.isNotEmpty()) {
                // SDL treats @scope on "ns" or "ns:x" as also granting "ns:private"; schemaScopes does not expand.
                val privateHint = if (unknown.any { it.endsWith(PRIVATE_SCOPE_SUFFIX) }) {
                    " A '$PRIVATE_SCOPE_SUFFIX' scope must be declared under schemaScopes in its own " +
                        "right, even though @scope(to: [...]) in SDL grants it implicitly."
                } else {
                    ""
                }
                errors += SchemaScopingValidationError(
                    code = ScopingErrorCodes.SCOPED_SCHEMA_UNKNOWN_SCOPE,
                    message = "Scoped schema '$id' references scope id(s) not declared under schemaScopes: " +
                        "$unknown. Declared scopes: ${scoping.scopeUniverse.sorted()}.$privateHint",
                )
            }
        }
        return errors
    }
}
