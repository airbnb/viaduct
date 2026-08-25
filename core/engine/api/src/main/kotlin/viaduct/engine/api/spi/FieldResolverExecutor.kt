package viaduct.engine.api.spi

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata

/**
 * Executor for a tenant-written resolver function.
 */
interface FieldResolverExecutor {
    /** The required selection set for the resolver */
    val objectSelectionSet: RequiredSelectionSet?

    /** The query selection set for the resolver **/
    val querySelectionSet: RequiredSelectionSet?

    /** Whether the resolver's result varies based on the requested field selections. */
    val isSelective: Boolean

    /** Same as field coordinate. Uniquely identifies a resolver function **/
    val resolverId: String

    /** Tenant-digestible metadata associated with this particular resolver */
    val metadata: ResolverMetadata

    /**
     * The input for a single node in the batch
     *
     * @param arguments The arguments for the field being resolved
     * @param selections The selections on the field being resolved, as requested by
     * the caller of this resolver, null if type does not support selections. Usually
     * used by tenants to examine what the client is querying
     * @param syncObjectValueGetter A suspending function returning the eagerly-resolved objectValue.
     * @param syncQueryValueGetter A suspending function returning the eagerly-resolved queryValue.
     */
    class Selector(
        val arguments: Map<String, Any?>,
        val selections: EngineSelectionSet?,
        val syncObjectValueGetter: suspend () -> EngineObjectData.Sync,
        val syncQueryValueGetter: suspend () -> EngineObjectData.Sync,
    ) {
        // syncObjectValueGetter identity distinguishes selectors for different items in a
        // batch (each item's lambda captures a different result container), which prevents
        // the DataLoader from collapsing distinct items into a single cache hit.
        // arguments and selections are included so that truly identical requests (same item,
        // same arguments, same field selections) can be deduplicated by the DataLoader cache.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Selector) return false
            return syncObjectValueGetter === other.syncObjectValueGetter &&
                arguments == other.arguments &&
                selections == other.selections
        }

        override fun hashCode(): Int {
            var result = System.identityHashCode(syncObjectValueGetter)
            result = 31 * result + arguments.hashCode()
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
