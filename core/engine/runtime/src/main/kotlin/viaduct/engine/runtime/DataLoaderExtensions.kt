package viaduct.engine.runtime

import viaduct.apiannotations.InternalApi
import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.runtime.EngineExecutionContextExtensions.asImpl
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy

private class BatchEngineExecutionContext(
    override val impl: EngineExecutionContextImpl,
    private val invocationContexts: Map<Any, EngineExecutionContext>,
) : InternalEngineExecutionContext by impl {
    fun invocationContextFor(selector: Any): EngineExecutionContext =
        checkNotNull(invocationContexts[selector]) {
            "No invocation context was captured for selector $selector"
        }
}

@InternalApi
fun EngineExecutionContext.invocationContextFor(selector: Any): EngineExecutionContext = (this as? BatchEngineExecutionContext)?.invocationContextFor(selector) ?: this

@InternalApi
fun EngineExecutionContext.withInvocationContexts(invocationContexts: Map<Any, EngineExecutionContext>): EngineExecutionContext =
    BatchEngineExecutionContext(
        impl = asImpl(),
        invocationContexts = invocationContexts,
    )

internal fun <K : Any, V, C : Any> DataLoader<K, V, C>.executionContextForBatchLoadFromKeys(
    keys: Set<K>,
    environment: BatchLoaderEnvironment<K>
): EngineExecutionContext {
    val context = keys.firstOrNull()?.let { firstKey ->
        environment.keyContexts[firstKey] as? EngineExecutionContext
    } ?: throw IllegalStateException("No EngineExecutionContext provided to internalLoad")

    return if (keys.size <= 1) {
        context
    } else {
        val invocationContexts = keys.associate { key ->
            key as Any to (
                environment.keyContexts[key] as? EngineExecutionContext
                    ?: throw IllegalStateException("No EngineExecutionContext provided for selector $key")
            )
        }
        BatchEngineExecutionContext(
            impl = context.copy(
                fieldScopeSupplier = { EngineExecutionContextImpl.FieldExecutionScopeImpl() },
                dataFetchingEnvironment = null,
                currentResolver = null,
            ),
            invocationContexts = invocationContexts,
        )
    }
}
