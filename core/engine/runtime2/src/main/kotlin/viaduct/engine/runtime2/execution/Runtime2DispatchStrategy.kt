package viaduct.engine.runtime2.execution

import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.ResolverDispatchStrategy

/**
 * Runtime2's current singleton-dispatch policy calls each resolver directly in the calling
 * coroutine, even when it supports batching. This avoids waiting for NextTickDispatcher,
 * which Runtime2 does not drive. Multi-item batching may require a different dispatch policy;
 * the resolver's isBatching flag indicates its capability, independently of this policy.
 */
internal object Runtime2DispatchStrategy : ResolverDispatchStrategy {
    override fun shouldDispatchImmediately(resolver: FieldResolverExecutor): Boolean = true

    override fun shouldDispatchImmediately(resolver: NodeResolverExecutor): Boolean = true
}
