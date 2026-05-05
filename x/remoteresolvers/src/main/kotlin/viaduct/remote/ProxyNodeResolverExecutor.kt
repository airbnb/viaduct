package viaduct.remote

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.NodeResolverExecutor

/**
 * [NodeResolverExecutor] decorator that delegates to a wrapped executor.
 *
 * Acts as a seam for layering experimental behavior (e.g. instrumentation,
 * remote execution) onto a single resolver type while leaving the rest
 * untouched.
 */
class ProxyNodeResolverExecutor(
    private val delegate: NodeResolverExecutor,
    override val typeName: String
) : NodeResolverExecutor {
    override val isBatching: Boolean = delegate.isBatching
    override val isSelective: Boolean = delegate.isSelective
    override val metadata: ResolverMetadata = delegate.metadata

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> = delegate.resolve(selectors, context)
}
