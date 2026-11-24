package viaduct.engine.runtime

import viaduct.apiannotations.InternalApi
import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext

@InternalApi
fun EngineExecutionContext.withInvocationContexts(invocationContexts: Map<Any, EngineExecutionContext>): EngineExecutionContext = (this as InternalEngineExecutionContext).forBatch(invocationContexts)

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
            executionContext = (context.executionContext as InternalEngineExecutionContext).forBatch(invocationContexts),
        )
    }
}
