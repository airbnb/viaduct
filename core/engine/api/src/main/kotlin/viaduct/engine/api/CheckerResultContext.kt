package viaduct.engine.api

/**
 * Context for evaluating a [CheckerResult].
 */
data class CheckerResultContext(
    /**
     * Directives applied to the field currently being read by resolver code, if available.
     */
    val fieldDirectives: FieldDirectives? = null,
)
