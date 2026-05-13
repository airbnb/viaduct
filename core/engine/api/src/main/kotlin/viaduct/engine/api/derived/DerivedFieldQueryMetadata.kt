package viaduct.engine.api.derived

/**
 * Metadata describing a derived-field provider's association with a root GraphQL query or mutation.
 *
 * Derived fields allow a tenant to annotate a resolver with a pre-declared sub-query that the
 * engine executes on its behalf. This class captures the bookkeeping data — query name, root
 * field, class path, owning tenant, and policy flags — that the engine needs to plan and execute
 * those sub-queries correctly.
 *
 * @property queryName The GraphQL operation name used for the internally generated sub-query.
 * @property rootFieldName The top-level field name on `Query` or `Mutation` that the derived-field
 *   provider calls into.
 * @property classPath Fully-qualified class path of the derived-field provider.
 * @property providerShortClasspath Short class path used for logging and diagnostics.
 * @property onRootQuery `true` if the root field lives on the `Query` type.
 * @property onRootMutation `true` if the root field lives on the `Mutation` type.
 * @property allowMutationOnQuery `true` if a mutation-backed derived field is permitted to run
 *   during a query operation (opt-in escape hatch; use with care).
 * @property fieldOwningTenant The tenant name that owns the root field, or `null` if unknown.
 * @property forceEngineResolution When `true`, bypasses any cached result and forces the engine
 *   to re-execute the sub-query.
 */
data class DerivedFieldQueryMetadata(
    val queryName: String,
    val rootFieldName: String,
    val classPath: String,
    val providerShortClasspath: String,
    val onRootQuery: Boolean,
    val onRootMutation: Boolean,
    val allowMutationOnQuery: Boolean,
    val fieldOwningTenant: String?,
    val forceEngineResolution: Boolean = false
)
