package viaduct.service.runtime.builtinresolvers

import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource

/**
 * Built-in [ExecutorFactory] for the `Query.node` / `Query.nodes` field resolvers.
 *
 * Instantiated by the file-based bootstrap path via the FQCN recorded in the built-in module
 * config produced by [QueryNodeModuleConfigFactory]. The `codeInjector` and `configSource`
 * constructor parameters exist to satisfy the reflective constructor contract shared with tenant
 * executor factories; built-in resolvers need neither.
 *
 * These resolvers are schema-independent singletons, so [createFieldResolverExecutor] maps each
 * config entry to the matching singleton by field name.
 */
class QueryNodeExecutorFactory(
    @Suppress("UNUSED_PARAMETER") codeInjector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") configSource: InputStreamSource,
) : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema
    ): FieldResolverExecutor =
        when (configData.fieldName) {
            "node" -> ViaductQueryNodeResolverModuleBootstrapper.queryNodeResolver
            "nodes" -> ViaductQueryNodeResolverModuleBootstrapper.queryNodesResolver
            else -> throw IllegalArgumentException(
                "QueryNodeExecutorFactory cannot create an executor for field '${configData.typeName}.${configData.fieldName}'"
            )
        }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema
    ): NodeResolverExecutor = throw UnsupportedOperationException("QueryNodeExecutorFactory does not create node resolver executors")
}
