package viaduct.service.api.scoping

import viaduct.apiannotations.ExperimentalApi

/**
 * Stable error codes for schema-scoping DSL validation.
 *
 * Mirrors the `ValidationErrorCodes` catalogue used by the SDL-level schema validator, but lives in
 * its own layer: these codes describe failures in the build-time `viaductApplication` scoping DSL
 * (setters + cross-property invariants), not SDL `@scope` usage. Codes are embedded in the
 * `[CODE] message` text of the `GradleException`s the plugin throws, so they are part of the
 * user-visible diagnostic surface and must stay stable.
 *
 * Using constants ensures consistency and lets tests assert on a code without matching free-form
 * prose.
 */
@ExperimentalApi
object ScopingErrorCodes {
    // ── Setter constraints (the single-call / non-empty / duplicate DSL guards) ─────────────────
    /** A second call to `declaredSchemaScopes`. */
    const val SCHEMA_SCOPES_DECLARED_TWICE = "SCHEMA_SCOPES_DECLARED_TWICE"

    /** `declaredSchemaScopes(emptySet())` — the call must carry at least one scope. */
    const val SCHEMA_SCOPES_EMPTY = "SCHEMA_SCOPES_EMPTY"

    /** A second call to `declaredScopedSchemas`. */
    const val SCOPED_SCHEMAS_DECLARED_TWICE = "SCOPED_SCHEMAS_DECLARED_TWICE"

    /** `declaredScopedSchemas()` with no entries. */
    const val SCOPED_SCHEMAS_EMPTY = "SCOPED_SCHEMAS_EMPTY"

    /** The same scoped-schema ID appears more than once within a single `declaredScopedSchemas` call. */
    const val SCOPED_SCHEMA_DUPLICATE_ID = "SCOPED_SCHEMA_DUPLICATE_ID"

    // ── Per-ID syntax (fire synchronously at setter time) ──────────────────────────────────────
    /** A scope ID does not match [SchemaScopingValidator.SCOPE_ID_PATTERN]. */
    const val SCOPE_ID_FORMAT_INVALID = "SCOPE_ID_FORMAT_INVALID"

    /** A scoped-schema ID does not match [SchemaScopingValidator.SCHEMA_ID_PATTERN]. */
    const val SCHEMA_ID_FORMAT_INVALID = "SCHEMA_ID_FORMAT_INVALID"

    /** A scoped-schema ID collides with a Viaduct-reserved name (see [SchemaScopingValidator.RESERVED_SCHEMA_IDS]). */
    const val SCHEMA_ID_RESERVED = "SCHEMA_ID_RESERVED"

    // ── Cross-property invariants (fire after declarations settle) ─────────────────────────────
    /** A scoped schema references scopes absent from the declared universe. */
    const val SCOPE_SET_NOT_SUBSET = "SCOPE_SET_NOT_SUBSET"

    /** Scoped schemas are declared but no scope universe is. */
    const val SCOPED_SCHEMAS_WITHOUT_UNIVERSE = "SCOPED_SCHEMAS_WITHOUT_UNIVERSE"
}
