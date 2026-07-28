package viaduct.service.runtime.builtinresolvers

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ResolverType
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector

/**
 * Built-in [ExecutorFactory] for the `Query.node` / `Query.nodes` field resolvers.
 *
 * Instantiated by the file-based bootstrap path via the FQCN recorded in the built-in module
 * config produced by [QueryNodeModuleConfigFactory]. The `codeInjector` and `registry`
 * constructor parameters exist to satisfy the reflective constructor contract shared with tenant
 * executor factories; built-in resolvers need neither.
 *
 * These resolvers are schema-independent singletons, so [createFieldResolverExecutor] maps each
 * config entry to the matching singleton by field name.
 */
class QueryNodeExecutorFactory(
    @Suppress("UNUSED_PARAMETER") codeInjector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    /**
     * GRT-prefix constructor — required so the bootstrap path can instantiate this factory when a
     * `grtPackagePrefix` override is in effect. Built-in resolvers are schema-independent and do no
     * GRT reflection, so the prefix is ignored.
     */
    constructor(
        codeInjector: CodeInjector,
        @Suppress("UNUSED_PARAMETER") grtPackagePrefix: String,
        registry: ExecutionRegistryConfigFile,
    ) : this(codeInjector, registry)

    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor =
        when (configData.fieldName) {
            "node" -> queryNodeResolver
            "nodes" -> queryNodesResolver
            else -> throw IllegalArgumentException(
                "QueryNodeExecutorFactory cannot create an executor for field '${configData.typeName}.${configData.fieldName}'"
            )
        }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor = throw UnsupportedOperationException("QueryNodeExecutorFactory does not create node resolver executors")

    companion object {
        /** Schema-independent singleton implementing the built-in `Query.node` resolver. */
        internal val queryNodeResolver = object : FieldResolverExecutor {
            override val objectSelectionSet: RequiredSelectionSet? = null
            override val querySelectionSet: RequiredSelectionSet? = null
            override val isSelective: Boolean = false
            override val resolverId: String = "Query.node"
            override val metadata: ResolverMetadata = ResolverMetadata.forModern("query-node-resolver", ResolverType.NODE)
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

        /** Schema-independent singleton implementing the built-in `Query.nodes` resolver. */
        internal val queryNodesResolver = object : FieldResolverExecutor {
            override val objectSelectionSet: RequiredSelectionSet? = null
            override val querySelectionSet: RequiredSelectionSet? = null
            override val isSelective: Boolean = false
            override val resolverId: String = "Query.nodes"
            override val metadata: ResolverMetadata = ResolverMetadata.forModern("query-nodes-resolver", ResolverType.FIELD)
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
