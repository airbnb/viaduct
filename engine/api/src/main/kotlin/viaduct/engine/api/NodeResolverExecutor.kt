package viaduct.engine.api

/**
 * Executes tenant-written node resolver or batch resolver function.
 */
interface NodeResolverExecutor {
    /**
     * The name of the Node type this resolves
     */
    val typeName: String

    /** Tenant-digestible metadata associated with this particular resolver */
    val metadata: ResolverMetadata

    /**
     * The input for a single node in the batch
     *
     * @param id: The serialized GlobalID of the Node being resolved
     * @param selections: The selections requested by the caller of this resolver
     */
    data class Selector(
        val id: String,
        val selections: RawSelectionSet
    )

    val isBatching: Boolean

    /**
     * Whether this resolver is selective (varies its response based on the selection set).
     *
     * When `true`, the resolver may return different data based on what fields are requested,
     * and cache entries must match both ID and selection set.
     *
     * When `false` (default), the resolver always returns its full output selection set,
     * and cache entries only need to match the ID.
     */
    val isSelective: Boolean

    /**
     * Executes the tenant-written resolver or batch resolver function, and unwraps
     * the result into a map of `Result<EngineObjectData>`.
     */
    suspend fun batchResolve(
        selectors: List<Selector>,
        context: EngineExecutionContext
    ): Map<Selector, Result<EngineObjectData>>
}
