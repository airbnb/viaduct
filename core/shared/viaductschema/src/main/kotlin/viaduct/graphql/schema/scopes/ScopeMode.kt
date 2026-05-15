package viaduct.graphql.schema.scopes

sealed interface ScopeMode

data class ScopedMode(
    val scopeUniverse: Set<String>,
    val scopedSchemas: Map<String, Set<String>>,
) : ScopeMode

object NoScopesMode : ScopeMode {
    override fun toString() = "NoScopesMode"
}
