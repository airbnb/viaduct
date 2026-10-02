package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * Stable string identifiers attached to every schema scope definition error surfaced to the user. Each
 * code follows the `CATEGORY_SPECIFIC_FAILURE` convention used elsewhere in the codebase (see
 * `viaduct.graphql.schema.validation.ValidationErrorCodes`).
 *
 * Codes appear in build output as `[CODE] message`. They are part of the user-facing contract:
 * downstream tooling and tests may match on them, so once published a code may be reworded but
 * should not be renamed without a deprecation cycle.
 */
@ExperimentalApi
object ScopingErrorCodes {
    /** `scopes.yaml` could not be parsed, or repeated a mapping key. */
    const val SCOPES_FILE_MALFORMED = "SCOPES_FILE_MALFORMED"

    /** `scopes.yaml` contained a key Viaduct does not recognize, at the top level or inside an entry. */
    const val SCOPES_FILE_UNKNOWN_KEY = "SCOPES_FILE_UNKNOWN_KEY"

    /** A value in `scopes.yaml` was the wrong shape or element type for its key. */
    const val SCOPES_FILE_WRONG_TYPE = "SCOPES_FILE_WRONG_TYPE"

    /** `scopes.yaml` declared definitions without a `version`. */
    const val SCOPES_FILE_VERSION_MISSING = "SCOPES_FILE_VERSION_MISSING"

    /** `scopes.yaml` declared a `version` this release does not support. */
    const val SCOPES_FILE_VERSION_UNSUPPORTED = "SCOPES_FILE_VERSION_UNSUPPORTED"

    /** A scope ID does not match the SDL identifier shape. */
    const val SCOPE_ID_FORMAT_INVALID = "SCOPE_ID_FORMAT_INVALID"

    /** A scoped-schema ID does not match the API-name identifier shape. */
    const val SCHEMA_ID_FORMAT_INVALID = "SCHEMA_ID_FORMAT_INVALID"

    /** A scoped-schema ID is reserved by Viaduct (`BASE`, `NONE`) and cannot be declared. */
    const val SCHEMA_ID_RESERVED = "SCHEMA_ID_RESERVED"

    /**
     * Scoped schemas were declared without a scope universe. Declaring scoped schemas implies the
     * application opts into scoping; the universe is the single decision point for which scope IDs
     * exist. The unscoped schema is requested by its reserved id at runtime and is not declared here.
     */
    const val SCOPED_SCHEMAS_WITHOUT_UNIVERSE = "SCOPED_SCHEMAS_WITHOUT_UNIVERSE"

    /** A scoped schema references scope IDs missing from the declared universe. */
    const val SCOPED_SCHEMA_UNKNOWN_SCOPE = "SCOPED_SCHEMA_UNKNOWN_SCOPE"

    /** A scoped schema selected no scopes. */
    const val SCOPED_SCHEMA_EMPTY_SCOPES = "SCOPED_SCHEMA_EMPTY_SCOPES"

    /** `schemaScopes` was declared but listed no scope IDs. */
    const val SCHEMA_SCOPES_EMPTY = "SCHEMA_SCOPES_EMPTY"

    /** A list of scope IDs contained the same ID more than once. */
    const val SCHEMA_SCOPE_DUPLICATE_ID = "SCHEMA_SCOPE_DUPLICATE_ID"

    /** Two `scopedSchemas` entries declared the same `id`. */
    const val SCOPED_SCHEMA_DUPLICATE_ID = "SCOPED_SCHEMA_DUPLICATE_ID"
}
