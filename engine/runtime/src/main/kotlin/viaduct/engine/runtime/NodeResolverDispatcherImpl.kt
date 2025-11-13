package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeResolverDispatcher
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ResolverMetadata

/**
 * Initialized via DispatcherRegistry and resolves a single node for a node type whose
 * tenant-written node resolver implements resolve or batch resolve function.
 *
 * If tenant implements the resolve function, it delegates to a dataloader that only
 * does caching and no batching, which dispatches immediately;
 *
 * If tenant implements the batch resolve function, it delegates to a dataloader that
 * handles batching and caching.
 */
class NodeResolverDispatcherImpl(
    private val resolver: NodeResolverExecutor
) : NodeResolverDispatcher {
    override val resolverMetadata: ResolverMetadata = resolver.metadata

    override suspend fun resolve(
        id: String,
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData {
        context as? EngineExecutionContextImpl ?: throw IllegalArgumentException(
            "Expected EngineExecutionContextImpl, got ${context::class.qualifiedName}"
        )
        val loader = context.nodeDataLoader(resolver)
        return loader.loadByKey(NodeResolverExecutor.Selector(id, selections), context).getOrThrow()
    }
}
