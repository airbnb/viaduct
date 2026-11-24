package viaduct.engine.runtime

import viaduct.apiannotations.InternalApi
import viaduct.engine.api.BatchExecutionContext
import viaduct.engine.api.EngineExecutionContext

/**
 * Engine-specific construction of contexts shared by a resolver batch.
 */
@InternalApi
interface InternalEngineExecutionContext : EngineExecutionContext {
    /**
     * Retains request state and loaders, clears caller-specific field state, and implements
     * [BatchExecutionContext] using [invocationContexts] for each selector's original context.
     */
    fun forBatch(invocationContexts: Map<Any, EngineExecutionContext>): EngineExecutionContext
}

/** Runtime1 decorators expose their implementation to Runtime1-only extensions. */
internal interface Runtime1EngineExecutionContext : InternalEngineExecutionContext {
    val impl: EngineExecutionContextImpl
}
