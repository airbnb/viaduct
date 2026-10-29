package viaduct.engine.runtime

import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.spi.FieldResolverExecutor

/**
 * The engine's dispatch strategy decides whether to collect calls into batches or dispatch immediately.
 * Currently the data loader doesn't support caching. We plan to add caching in the future.
 */
class FieldDataLoader(
    private val resolver: FieldResolverExecutor,
    private val dispatchStrategy: ResolverDispatchStrategy = RuntimeDispatchStrategy,
) : DataLoader<FieldResolverExecutor.Selector, Result<Any?>, FieldResolverExecutor.Selector>() {
    suspend fun loadByKey(
        key: FieldResolverExecutor.Selector,
        context: EngineExecutionContext,
        instrumentationContext: ResolverInstrumentationContext? = null,
    ): Result<Any?> {
        return internalDataLoader.load(
            key,
            ResolverInvocationContext(context, instrumentationContext)
        ) ?: throw IllegalStateException("Received null FieldDataLoader value for field: ${resolver.resolverId}")
    }

    override suspend fun internalLoad(
        keys: Set<FieldResolverExecutor.Selector>,
        environment: BatchLoaderEnvironment<FieldResolverExecutor.Selector>
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        val invocation = invocationContextForBatchLoadFromKeys(keys, environment)
        return resolver.batchResolve(keys.toList(), invocation.executionContext, invocation.instrumentationContext)
    }

    override fun shouldUseImmediateDispatch(): Boolean = dispatchStrategy.shouldDispatchImmediately(resolver)
}
