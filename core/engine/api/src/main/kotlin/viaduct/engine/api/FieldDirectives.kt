package viaduct.engine.api

/**
 * Directives applied to a selected field, exposed without leaking the underlying
 * GraphQL Java representation through the engine API.
 */
interface FieldDirectives {
    /**
     * Return true if the selected field has directive [name].
     *
     * If [args] is provided, it is called with the directive's coerced argument values.
     * The directive only matches when [args] returns true.
     */
    fun hasDirective(
        name: String,
        args: ((Map<String, Any?>) -> Boolean)? = null,
    ): Boolean
}
