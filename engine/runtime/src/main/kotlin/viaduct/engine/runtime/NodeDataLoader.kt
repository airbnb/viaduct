package viaduct.engine.runtime

import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeResolverExecutor

/**
 * If the resolver is a batch resolver, then the data loader handles batching together calls to
 * the `batchResolve` function of a node resolver, and caching the results.
 * If the resolver is a non-batch resolver, then the data loader only caches the results and no
 * batching is done.
 * There should be exactly one instance of this data loader per node type per request.
 */
class NodeDataLoader(
    private val resolver: NodeResolverExecutor
) : DataLoader<NodeResolverExecutor.Selector, Result<EngineObjectData>, NodeResolverExecutor.Selector>() {
    suspend fun loadByKey(
        key: NodeResolverExecutor.Selector,
        context: EngineExecutionContext
    ): Result<EngineObjectData> {
        return internalDataLoader.load(key, context) ?: throw IllegalStateException("Received null NodeDataLoader value for node ID: ${key.id}")
    }

    override suspend fun internalLoad(
        keys: Set<NodeResolverExecutor.Selector>,
        environment: BatchLoaderEnvironment<NodeResolverExecutor.Selector>
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>?> {
        return resolver.batchResolve(keys.toList(), executionContextForBatchLoadFromKeys(keys, environment))
    }

    override fun shouldUseImmediateDispatch(): Boolean = !resolver.isBatching

    override val cacheKeyMatchFn get() = { newKey: NodeResolverExecutor.Selector, cachedKey: NodeResolverExecutor.Selector ->
        cachedKey.covers(newKey, resolver.isSelective)
    }
}

/**
 * Returns true if the receiver covers [other], such its value can be used instead of executing the
 * resolver with the [other] selector.
 *
 * @param isSelective Whether the resolver varies its response based on the selection set.
 *   When false (non-selective), only ID matching is required for cache hits.
 *   When true (selective), selection set coverage must also be checked.
 */
internal fun NodeResolverExecutor.Selector.covers(
    other: NodeResolverExecutor.Selector,
    isSelective: Boolean
): Boolean {
    if (other.id != this.id) return false

    // Non-selective resolvers always return their full output selection set,
    // so ID match is sufficient for cache hits
    if (!isSelective) return true

    // Selective resolvers may vary their response based on requested fields,
    // so we need to verify the cached entry covers all requested fields
    // Consider "id" to be part of the selection set if it isn't already
    val selectedFields = this.selections.selections().mapTo(mutableSetOf("id")) { it.fieldName }
    return other.selections.selections().all { it.fieldName in selectedFields }
}
