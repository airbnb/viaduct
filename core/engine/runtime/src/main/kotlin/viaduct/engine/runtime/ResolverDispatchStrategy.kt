package viaduct.engine.runtime

import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor

interface ResolverDispatchStrategy {
    fun shouldDispatchImmediately(resolver: FieldResolverExecutor): Boolean

    fun shouldDispatchImmediately(resolver: NodeResolverExecutor): Boolean
}

internal object RuntimeDispatchStrategy : ResolverDispatchStrategy {
    override fun shouldDispatchImmediately(resolver: FieldResolverExecutor): Boolean = !resolver.isBatching

    override fun shouldDispatchImmediately(resolver: NodeResolverExecutor): Boolean = !resolver.isBatching
}
