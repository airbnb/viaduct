package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * Stable string identifiers attached to every schema-scoping configuration error surfaced to the
 * user. Each code follows the `CATEGORY_SPECIFIC_FAILURE` convention used elsewhere in the
 * codebase (see `viaduct.graphql.schema.validation.ValidationErrorCodes`).
 *
 * Codes appear in build output as `[CODE] message`. They are part of the user-facing contract:
 * downstream tooling and tests may match on them, so once published a code may be reworded but
 * should not be renamed without a deprecation cycle.
 */
@ExperimentalApi
object ScopingErrorCodes {
    /** DSL rejected a scope ID that does not match the SDL identifier shape. */
    const val SCOPE_ID_FORMAT_INVALID = "SCOPE_ID_FORMAT_INVALID"

    /** DSL rejected a scoped-schema ID that does not match the API-name identifier shape. */
    const val SCHEMA_ID_FORMAT_INVALID = "SCHEMA_ID_FORMAT_INVALID"

    /** DSL rejected a scoped-schema ID that is reserved by Viaduct (`FULL`, `NONE`). */
    const val SCHEMA_ID_RESERVED = "SCHEMA_ID_RESERVED"

    /**
     * `SchemaScopingBuilder.build()` rejected a configuration that declares scoped schemas but no
     * scope universe. Declaring scoped schemas implies the application opts into scoping; the
     * universe is the single decision point for which scope IDs exist. The full-schema path is
     * exposed by other means and does not require the `declareScoping` block.
     */
    const val SCOPED_SCHEMAS_WITHOUT_UNIVERSE = "SCOPED_SCHEMAS_WITHOUT_UNIVERSE"

    /** `SchemaScopingBuilder.build()` rejected a scoped schema that references scope IDs missing from the universe. */
    const val SCOPED_SCHEMA_UNKNOWN_SCOPE = "SCOPED_SCHEMA_UNKNOWN_SCOPE"

    /** DSL rejected a second `scopes(...)` call inside the same `declareScoping` block. */
    const val SCHEMA_SCOPES_DECLARED_TWICE = "SCHEMA_SCOPES_DECLARED_TWICE"

    /** DSL rejected a `scopes(...)` call with no scope IDs. */
    const val SCHEMA_SCOPES_EMPTY = "SCHEMA_SCOPES_EMPTY"

    /** DSL rejected a `scopes(...)` call whose vararg list contained the same scope ID more than once. */
    const val SCHEMA_SCOPE_DUPLICATE_ID = "SCHEMA_SCOPE_DUPLICATE_ID"

    /** DSL rejected a `scopedSchema(id, ...)` call where the same schema ID had already been declared in this block. */
    const val SCOPED_SCHEMA_DUPLICATE_ID = "SCOPED_SCHEMA_DUPLICATE_ID"

    /** DSL rejected a second call to `declareScoping { ... }` on the same application. */
    const val SCHEMA_SCOPING_DECLARED_TWICE = "SCHEMA_SCOPING_DECLARED_TWICE"
}
