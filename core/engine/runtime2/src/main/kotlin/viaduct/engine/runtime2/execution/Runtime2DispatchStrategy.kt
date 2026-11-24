package viaduct.engine.runtime2.execution

import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.ResolverDispatchStrategy

/**
 * Immediate dispatch for nodes and for fields when Runtime2 batching is disabled.
 * Batching-enabled field invocations select the shared runtime dispatch strategy instead.
 */
internal object Runtime2DispatchStrategy : ResolverDispatchStrategy {
    override fun shouldDispatchImmediately(resolver: FieldResolverExecutor): Boolean = true

    override fun shouldDispatchImmediately(resolver: NodeResolverExecutor): Boolean = true
}
