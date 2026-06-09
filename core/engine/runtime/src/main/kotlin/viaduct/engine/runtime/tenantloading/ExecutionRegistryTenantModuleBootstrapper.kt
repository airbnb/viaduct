@file:Suppress("DEPRECATION") // legacy bootstrap shim

package viaduct.engine.runtime.tenantloading

import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.LegacyTenantModuleBootstrapper
import viaduct.engine.api.spi.NodeResolverExecutor

class ExecutionRegistryTenantModuleBootstrapper(
    private val registry: ExecutionRegistryConfigFile,
    private val executorFactory: ExecutorFactory,
) : LegacyTenantModuleBootstrapper {
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
}
