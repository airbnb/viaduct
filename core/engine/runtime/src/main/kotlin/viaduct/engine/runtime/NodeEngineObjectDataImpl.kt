package viaduct.engine.runtime

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.NodeReference

class NodeEngineObjectDataImpl(
    override val id: String,
    override val type: GraphQLObjectType,
    private val dispatcherRegistry: DispatcherRegistry
) : NodeEngineObjectData, NodeReference, LazyEngineObjectData {
    private val resolveOnce = ResolveOnce<EngineObjectData>()

    override suspend fun fetch(selection: String): Any? {
        if (selection == "id") return id
        return resolveOnce.await().fetch(selection)
    }

    override suspend fun fetchOrNull(selection: String): Any? {
        if (selection == "id") return id
        return resolveOnce.await().fetchOrNull(selection)
    }

    override suspend fun fetchSelections(): Iterable<String> = resolveOnce.await().fetchSelections()

    override suspend fun resolveData(
        selections: EngineSelectionSet,
        context: EngineExecutionContext
    ): EngineObjectData =
        resolveOnce.resolve {
            val nodeResolver = dispatcherRegistry.getNodeResolverDispatcher(type.name)
                ?: throw IllegalStateException("No node resolver found for type ${type.name}")
            nodeResolver.resolve(id, selections, context)
        }
}
