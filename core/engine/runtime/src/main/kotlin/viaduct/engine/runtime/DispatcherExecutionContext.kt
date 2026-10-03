package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor

/**
 * Request-owned capabilities required by resolver dispatchers.
 *
 * Keeping loader lookup on this interface lets multiple engine implementations invoke the same
 * dispatchers without requiring their contexts to be [EngineExecutionContextImpl].
 */
interface DispatcherExecutionContext : EngineExecutionContext {
    fun fieldDataLoader(resolver: FieldResolverExecutor): FieldDataLoader

    fun nodeDataLoader(resolver: NodeResolverExecutor): NodeDataLoader
}
