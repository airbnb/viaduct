package viaduct.service.runtime.noderesolvers

import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeReference
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.engine.api.ViaductSchema

/**
 * ViaductNodeResolverModuleBootstrapper is responsible for defining and bootstrapping system level Query.node/s field resolvers.
 */
class ViaductQueryNodeResolverModuleBootstrapper : TenantModuleBootstrapper {
    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> =
        buildList {
            if (schema.schema.queryType.getFieldDefinition("node") != null) {
                add(Coordinate("Query", "node") to queryNodeResolver)
            }
            if (schema.schema.queryType.getFieldDefinition("nodes") != null) {
                add(Coordinate("Query", "nodes") to queryNodesResolver)
            }
        }

    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> {
        return emptyList()
    }

    companion object {
        // Internal for testing
        internal val queryNodeResolver = object : FieldResolverExecutor {
            override val objectSelectionSet: RequiredSelectionSet? = null
            override val querySelectionSet: RequiredSelectionSet? = null
            override val resolverId: String = "Query.node"
            override val metadata: ResolverMetadata = ResolverMetadata.forModern("query-node-resolver")
            override val isBatching: Boolean = false

            override suspend fun batchResolve(
                selectors: List<FieldResolverExecutor.Selector>,
                context: EngineExecutionContext
            ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
                // Only handle single selector case because this is an unbatched resolver
                require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got ${selectors.size}" }
                val selector = selectors.first()

                return mapOf(
                    selector to runCatching {
                        val globalId = selector.arguments["id"]
                        resolveNodeByGlobalId(globalId, context)
                    }
                )
            }
        }

        // Internal for testing
        internal val queryNodesResolver = object : FieldResolverExecutor {
            override val objectSelectionSet: RequiredSelectionSet? = null
            override val querySelectionSet: RequiredSelectionSet? = null
            override val resolverId: String = "Query.nodes"
            override val metadata: ResolverMetadata = ResolverMetadata.forModern("query-nodes-resolver")
            override val isBatching: Boolean = false

            override suspend fun batchResolve(
                selectors: List<FieldResolverExecutor.Selector>,
                context: EngineExecutionContext
            ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
                require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got ${selectors.size}" }
                val selector = selectors.first()
                return mapOf(
                    selector to runCatching {
                        val globalIds = selector.arguments["ids"]
                        require(globalIds is List<*>) { "Expected 'ids' argument to be a list. This should never occur." }
                        globalIds.map { id ->
                            resolveNodeByGlobalId(id, context)
                        }
                    }
                )
            }
        }

        /**
         * Resolves and validates a globalId via schema introspection.
         */
        private fun resolveNodeByGlobalId(
            globalId: Any?,
            context: EngineExecutionContext
        ): NodeReference {
            require(globalId is String) { "Expected GlobalID \"$globalId\" to be a string. This should never occur." }
            val (typeName, _) = context.globalIDCodec.deserialize(globalId)

            val graphQLObjectType = context.fullSchema.schema.getObjectType(typeName)
            requireNotNull(graphQLObjectType) { "Expected GlobalId \"$globalId\" with type name '$typeName' to match a named object type in the schema" }

            val implementsNode = graphQLObjectType.interfaces.any { it.name == "Node" }
            require(implementsNode) { "Expected GlobalId \"$globalId\" with type name '$typeName' to match a named object type that extends the Node interface" }

            return context.createNodeReference(globalId, graphQLObjectType)
        }
    }
}
