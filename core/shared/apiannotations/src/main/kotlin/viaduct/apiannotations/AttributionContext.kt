package viaduct.apiannotations

/**
 * The error-attribution execution context in which a function runs.
 *
 * Used with [@Attribution] to override the module-default context for a specific function.
 */
enum class AttributionContext {
    /**
     * The annotated function executes in *tenant context*: unattributed exceptions are
     * attributed to tenant code.
     */
    TENANT,

    /**
     * The annotated function executes in *framework context*: unattributed exceptions are
     * attributed to framework code.
     */
    FRAMEWORK,

    /**
     * The annotated function inherits its caller's attribution context. Useful for utility
     * code that should not shift attribution.
     */
    INHERIT_CALLER,
}
