package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleBootstrapper
import viaduct.engine.api.spi.TenantModuleException

/**
 * Entries naming types or fields absent from the schema are filtered out, since a module may be
 * compiled against a superset of the schema it is bootstrapped with.
 *
 * @throws TenantModuleException if the registry declares two entries at the same coordinate.
 */
class ModuleResolvers(
    private val registry: ExecutionRegistryConfigFile,
    private val executorFactory: ExecutorFactory,
) : TenantModuleBootstrapper {
    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> {
        val filtered = filterFieldsBySchema(registry.fields, schema)
        validateFields(filtered)
        return filtered.map { entry ->
            (entry.typeName to entry.fieldName) to executorFactory.createFieldResolverExecutor(entry, schema)
        }
    }

    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> {
        val filtered = filterNodesBySchema(registry.nodes, schema)
        validateNodes(filtered)
        return filtered.map { entry ->
            entry.typeName to executorFactory.createNodeResolverExecutor(entry, schema)
        }
    }

    override fun toString(): String = "ModuleResolvers(tenant=${registry.tenantName}, executorFactory=${registry.executorFactory})"
}
