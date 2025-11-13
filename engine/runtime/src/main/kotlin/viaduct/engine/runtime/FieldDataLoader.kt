package viaduct.engine.runtime

import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FieldResolverExecutor

/**
 * If the resolver is a batch resolver, then the data loader handles batching together calls to
 * the `batchResolve` function of a field resolver.
 * If the resolver is a non-batch resolver, then the data loader doesn't do any batching and simply
 * calls the `resolve` function of a field resolver.
 * Currently the data loader doesn't support caching. We plan to add caching in the future.
 * There should be exactly one instance of this data loader per field coordinate per request.
 */
class FieldDataLoader(
    private val resolver: FieldResolverExecutor
) : DataLoader<FieldResolverExecutor.Selector, Result<Any?>, FieldResolverExecutor.Selector>() {
    suspend fun loadByKey(
        key: FieldResolverExecutor.Selector,
        context: EngineExecutionContext
    ): Result<Any?> {
        return internalDataLoader.load(key, context) ?: throw IllegalStateException("Received null FieldDataLoader value for field: ${resolver.resolverId}")
    }

    override suspend fun internalLoad(
        keys: Set<FieldResolverExecutor.Selector>,
        environment: BatchLoaderEnvironment<FieldResolverExecutor.Selector>
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        return resolver.batchResolve(keys.toList(), executionContextForBatchLoadFromKeys(keys, environment))
    }

    override fun shouldUseImmediateDispatch(): Boolean = !resolver.isBatching
}
