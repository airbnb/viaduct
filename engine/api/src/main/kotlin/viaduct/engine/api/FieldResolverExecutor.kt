package viaduct.engine.api

/**
 * Executor for a tenant-written resolver function.
 */
interface FieldResolverExecutor {
    /** The required selection set for the resolver */
    val objectSelectionSet: RequiredSelectionSet?

    /** The query selection set for the resolver **/
    val querySelectionSet: RequiredSelectionSet?

    /** Same as field coordinate. Uniquely identifies a resolver function **/
    val resolverId: String

    /** Tenant-digestible metadata associated with this particular resolver */
    val metadata: ResolverMetadata

    /**
     * The input for a single node in the batch
     *
     * @param arguments The arguments for the field being resolved
     * @param objectValue The result of executing the required selection set
     * @param queryValue The result of executing the query selection set
     * @param selections The selections on the field being resolved, as requested by
     * the caller of this resolver, null if type does not support selections. Usually
     * used by tenants to examine what the client is querying
     * @param syncObjectValueGetter A suspending function that, when called, returns a synchronously-
     * accessible version of [objectValue]. All selections will have been eagerly resolved.
     * Null if not available (for backward compatibility).
     * @param syncQueryValueGetter A suspending function that, when called, returns a synchronously-
     * accessible version of [queryValue]. All selections will have been eagerly resolved.
     * Null if not available (for backward compatibility).
     */
    class Selector(
        val arguments: Map<String, Any?>,
        val objectValue: EngineObjectData,
        val queryValue: EngineObjectData,
        val selections: RawSelectionSet?,
        val syncObjectValueGetter: (suspend () -> EngineObjectData.Sync)? = null,
        val syncQueryValueGetter: (suspend () -> EngineObjectData.Sync)? = null,
    ) {
        // Manual equals/hashCode to exclude lambda properties from comparison.
        // This is critical for DataLoader batching - lambdas create unique instances
        // that would otherwise make identical selectors appear unequal.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Selector) return false
            return arguments == other.arguments &&
                objectValue == other.objectValue &&
                queryValue == other.queryValue &&
                selections == other.selections
        }

        override fun hashCode(): Int {
            var result = arguments.hashCode()
            result = 31 * result + objectValue.hashCode()
            result = 31 * result + queryValue.hashCode()
            result = 31 * result + (selections?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * Whether or not this resolver supports batch resolution.
     * If true, the resolver can be called with a list of selectors.
     * If false, the resolver must be called with a single selector.
     */
    val isBatching: Boolean

    /**
     * Returns true if this resolver has a required selection set, either on the parent object or on Query.
     */
    fun hasRequiredSelectionSets() = objectSelectionSet != null || querySelectionSet != null

    /**
     * Resolves a list of selectors in a batch if isBatching is true.
     * If isBatching is false, it enforces the selectors list size to be 1.
     *
     * @param selector The input to resolve
     * @param context The execution context for the resolver
     * @return A map of selectors to their resolved results.
     */
    suspend fun batchResolve(
        selectors: List<Selector>,
        context: EngineExecutionContext
    ): Map<Selector, Result<Any?>>
}
