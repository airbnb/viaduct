package viaduct.bootstrap

/**
 * Identity of a single execution registry configuration: the tenant module that owns it plus the
 * tenant API implementation that produced it.
 *
 * The inputs to one dispatcher-registry build form a map keyed by this type — at most one
 * configuration per key. Notably absent is the executor factory: it selects *how* a configuration is
 * materialized into executors, but an API implementation may rename or replace its factory class
 * without becoming a different API, so the factory cannot identify the configuration slot.
 *
 * See `projects/viaduct/oss/impldocs/execution-registry-bootstrap.md`.
 */
data class ConfigKey(
    val tenantName: String,
    val apiName: String,
) {
    /** Renders as `<tenantName, apiName>`, the form used in diagnostics and the impldoc. */
    override fun toString(): String = "<$tenantName, $apiName>"
}
