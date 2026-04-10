package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext

/**
 * Internal interface extending [EngineExecutionContext] that exposes the underlying
 * [EngineExecutionContextImpl].
 *
 * Both [EngineExecutionContextImpl] and decorator classes (e.g. InstrumentedEngineExecutionContext)
 * implement this interface, allowing engine-internal code to access the underlying impl
 * without a direct cast to the concrete class.
 */
internal interface InternalEngineExecutionContext : EngineExecutionContext {
    val impl: EngineExecutionContextImpl
}
