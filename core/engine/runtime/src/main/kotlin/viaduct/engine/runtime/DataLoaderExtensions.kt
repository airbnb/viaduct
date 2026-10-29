package viaduct.engine.runtime

import viaduct.apiannotations.InternalApi
import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.engine.api.BatchExecutionContext
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.EngineExecutionContextExtensions.asImpl
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy

private class BatchEngineExecutionContext(
    override val impl: EngineExecutionContextImpl,
    private val invocationContexts: Map<Any, EngineExecutionContext>,
) : BatchExecutionContext,
    InternalEngineExecutionContext by impl,
    DispatcherExecutionContext {
    override fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader = impl.fieldDataLoader(resolver)

    override fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader = impl.nodeDataLoader(resolver)

    override fun invocationContextFor(selector: Any): EngineExecutionContext =
        checkNotNull(invocationContexts[selector]) {
            "No invocation context was captured for selector $selector"
        }
}

@InternalApi
fun EngineExecutionContext.withInvocationContexts(invocationContexts: Map<Any, EngineExecutionContext>): EngineExecutionContext =
    BatchEngineExecutionContext(
        impl = asImpl(),
        invocationContexts = invocationContexts,
    )

internal data class ResolverInvocationContext(
    val executionContext: EngineExecutionContext,
    val instrumentationContext: ResolverInstrumentationContext?,
)

internal fun <K : Any, V, C : Any> DataLoader<K, V, C>.invocationContextForBatchLoadFromKeys(
    keys: Set<K>,
    environment: BatchLoaderEnvironment<K>
): ResolverInvocationContext {
    val context = keys.firstOrNull()?.let { firstKey ->
        environment.keyContexts[firstKey] as? ResolverInvocationContext
    } ?: throw IllegalStateException("No resolver invocation context provided to internalLoad")

    return if (keys.size <= 1) {
        context
    } else {
        val invocationContexts = keys.associate { key ->
            key as Any to (
                environment.keyContexts[key] as? ResolverInvocationContext
                    ?: throw IllegalStateException("No resolver invocation context provided for selector $key")
            ).executionContext
        }
        context.copy(
            executionContext = BatchEngineExecutionContext(
                impl = context.executionContext.copy(
                    fieldScopeSupplier = { EngineExecutionContextImpl.FieldExecutionScopeImpl() },
                    dataFetchingEnvironment = null,
                    currentResolver = null,
                ),
                invocationContexts = invocationContexts,
            ),
        )
    }
}
