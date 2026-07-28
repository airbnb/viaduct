package viaduct.service.runtime.builtinresolvers

import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigFactory
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource

/**
 * [ModuleConfigFactory] for the built-in `Query.node` / `Query.nodes` resolvers.
 *
 * Inspects [fullSchema] for the presence of `Query.node` and `Query.nodes` and generates a module
 * config that routes those fields through [QueryNodeExecutorFactory], so the built-in resolvers
 * flow through the same file-based bootstrap path as tenant modules.
 *
 * Returns `null` when the schema declares neither field (nothing to contribute).
 */
class QueryNodeModuleConfigFactory(
    private val fullSchema: ViaductSchema,
) : ModuleConfigFactory {
    override fun moduleConfigSource(): ModuleConfigSource? {
        val queryType = fullSchema.schema.queryType
        val fields = buildList {
            if (queryType.getFieldDefinition("node") != null) add(builtinFieldEntry("Query", "node", ATTRIBUTION))
            if (queryType.getFieldDefinition("nodes") != null) add(builtinFieldEntry("Query", "nodes", ATTRIBUTION))
        }
        if (fields.isEmpty()) return null

        return buildBuiltinModuleConfigSource(
            tenantName = TENANT_NAME,
            executorFactoryName = QueryNodeExecutorFactory::class.java.name,
            fields = fields,
        )
    }

    companion object {
        /** Stable generated source name for this built-in module. */
        const val TENANT_NAME = "viaduct.builtin.query_node"
        private const val ATTRIBUTION = "query-node-resolver"
    }
}
