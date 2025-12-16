package viaduct.engine.runtime

import viaduct.dataloader.BatchLoaderEnvironment
import viaduct.dataloader.DataLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy

internal fun <K : Any, V, C : Any> DataLoader<K, V, C>.executionContextForBatchLoadFromKeys(
    keys: Set<K>,
    environment: BatchLoaderEnvironment<K>
): EngineExecutionContext {
    // For batch resolvers, all keys should share the same context
    // For non-batch resolvers (immediate dispatch), there's only one key
    val context = keys.firstOrNull()?.let { firstKey ->
        environment.keyContexts[firstKey] as? EngineExecutionContext
    } ?: throw IllegalStateException("No EngineExecutionContext provided to internalLoad")

    return if (keys.size <= 1) {
        context
    } else {
        // For batch resolution, clear out any field-specific scope that would not be applicable to all keys
        context.copy(fieldScopeSupplier = { EngineExecutionContextImpl.FieldExecutionScopeImpl() }, dataFetchingEnvironment = null)
    }
}
