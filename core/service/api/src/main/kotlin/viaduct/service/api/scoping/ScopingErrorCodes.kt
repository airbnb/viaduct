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
    /** DSL setter rejected a scope ID that does not match the SDL identifier shape. */
    const val SCOPE_ID_FORMAT_INVALID = "SCOPE_ID_FORMAT_INVALID"

    /** DSL setter rejected a scoped-schema ID that does not match the API-name identifier shape. */
    const val SCHEMA_ID_FORMAT_INVALID = "SCHEMA_ID_FORMAT_INVALID"

    /** DSL setter rejected a scoped-schema ID that is reserved by Viaduct (`FULL`, `NONE`). */
    const val SCHEMA_ID_RESERVED = "SCHEMA_ID_RESERVED"

    /**
     * `afterEvaluate` rejected a configuration that declares scoped schemas but no scope universe.
     * Declaring scoped schemas implies the application opts into scoping; the universe is the
     * single decision point for which scope IDs exist. The full-schema path is exposed by other
     * means and does not require this DSL pair.
     */
    const val SCOPED_SCHEMAS_WITHOUT_UNIVERSE = "SCOPED_SCHEMAS_WITHOUT_UNIVERSE"

    /** `afterEvaluate` rejected a scoped schema that references scope IDs missing from the universe. */
    const val SCOPED_SCHEMA_UNKNOWN_SCOPE = "SCOPED_SCHEMA_UNKNOWN_SCOPE"

    /** DSL setter rejected a second call to `declaredSchemaScopes`. */
    const val SCHEMA_SCOPES_DECLARED_TWICE = "SCHEMA_SCOPES_DECLARED_TWICE"

    /** DSL setter rejected a `declaredSchemaScopes` call with no scope IDs. */
    const val SCHEMA_SCOPES_EMPTY = "SCHEMA_SCOPES_EMPTY"

    /** DSL setter rejected a second call to `declaredScopedSchemas`. */
    const val SCOPED_SCHEMAS_DECLARED_TWICE = "SCOPED_SCHEMAS_DECLARED_TWICE"

    /** DSL setter rejected a `declaredScopedSchemas` call with no entries. */
    const val SCOPED_SCHEMAS_EMPTY = "SCOPED_SCHEMAS_EMPTY"

    /** DSL setter rejected a `declaredScopedSchemas` call where the same schema ID appeared twice. */
    const val SCOPED_SCHEMA_DUPLICATE_ID = "SCOPED_SCHEMA_DUPLICATE_ID"
}
