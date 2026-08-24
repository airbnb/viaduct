package viaduct.arbitrary.graphql

import javax.inject.Provider
import viaduct.engine.api.Coordinate
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ModuleConfigSource
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.InputStreamSource
import viaduct.service.api.spi.TenantModuleInjectorFactory

/**
 * The generated executors belonging to one arbitrary Viaduct instance.
 *
 * Duplicates equivalent logic in engine/api's test fixtures: sharing it would put this module and
 * engine/api in a dependency cycle.
 */
internal class ArbitraryExecutors(
    fieldResolverExecutors: Iterable<Pair<Coordinate, FieldResolverExecutor>>,
    nodeResolverExecutors: Iterable<Pair<String, NodeResolverExecutor>>,
) {
    private val fields = fieldResolverExecutors.toMap()
    private val nodes = nodeResolverExecutors.toMap()

    fun moduleConfigSource(tenantName: String): ModuleConfigSource {
        val config = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = ArbitraryExecutorFactory::class.java.name,
            tenantName = tenantName,
            apiName = "arbitrary",
            fields = fields.map { (coord, executor) ->
                FieldEntryConfig(
                    typeName = coord.first,
                    fieldName = coord.second,
                    isBatching = executor.isBatching,
                    isSelective = executor.isSelective,
                    attribution = executor.metadata.name,
                    tenantAPIData = emptyMap(),
                )
            },
            nodes = nodes.map { (typeName, executor) ->
                NodeEntryConfig(
                    typeName = typeName,
                    isBatching = executor.isBatching,
                    isSelective = executor.isSelective,
                    attribution = executor.metadata.name,
                    tenantAPIData = emptyMap(),
                )
            },
        )
        return ModuleConfigSource.from(
            InputStreamSource.fromString(
                ExecutionRegistryConfigFile.toJson(config),
                name = tenantName,
            ),
        )
    }

    fun fieldExecutor(config: FieldEntryConfig): FieldResolverExecutor = requireNotNull(fields[config.typeName to config.fieldName])

    fun nodeExecutor(config: NodeEntryConfig): NodeResolverExecutor = requireNotNull(nodes[config.typeName])
}

internal class ArbitraryExecutorCodeInjector(
    private val executors: ArbitraryExecutors,
) : CodeInjector, TenantModuleInjectorFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <T> getProvider(clazz: Class<T>): Provider<T> =
        if (clazz == ArbitraryExecutors::class.java) {
            Provider { executors as T }
        } else {
            CodeInjector.Naive.getProvider(clazz)
        }

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector = this
}

/** Reflective executor factory for configs produced by [ArbitraryExecutors]. */
internal class ArbitraryExecutorFactory(
    codeInjector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    private val executors = codeInjector.getProvider(ArbitraryExecutors::class.java).get()

    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema,
    ): FieldResolverExecutor = executors.fieldExecutor(configData)

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema,
    ): NodeResolverExecutor = executors.nodeExecutor(configData)
}
