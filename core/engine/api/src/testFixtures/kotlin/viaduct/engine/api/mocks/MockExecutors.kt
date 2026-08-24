package viaduct.engine.api.mocks

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

/** The field and node executors belonging to one mock module. */
class MockExecutorRegistry(
    fieldResolverExecutors: Iterable<Pair<Coordinate, FieldResolverExecutor>>,
    nodeResolverExecutors: Iterable<Pair<String, NodeResolverExecutor>>,
) {
    internal val fields = fieldResolverExecutors.toMap()
    internal val nodes = nodeResolverExecutors.toMap()

    internal fun fieldExecutor(coordinate: Coordinate): FieldResolverExecutor = requireNotNull(fields[coordinate])

    internal fun nodeExecutor(typeName: String): NodeResolverExecutor = requireNotNull(nodes[typeName])
}

internal fun mockModuleConfigSource(
    tenantName: String,
    registry: MockExecutorRegistry,
): ModuleConfigSource {
    val config =
        ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = MockExecutorFactory::class.java.name,
            tenantName = tenantName,
            apiName = "mock",
            fields = registry.fields.map { (coordinate, executor) ->
                FieldEntryConfig(
                    typeName = coordinate.first,
                    fieldName = coordinate.second,
                    isBatching = executor.isBatching,
                    isSelective = executor.isSelective,
                    attribution = executor.metadata.name,
                    tenantAPIData = emptyMap(),
                )
            },
            nodes = registry.nodes.map { (typeName, executor) ->
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

class MockExecutorCodeInjector(
    private val registry: MockExecutorRegistry,
) : CodeInjector, TenantModuleInjectorFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <T> getProvider(clazz: Class<T>): Provider<T> =
        if (clazz == MockExecutorRegistry::class.java) {
            Provider { registry as T }
        } else {
            CodeInjector.Naive.getProvider(clazz)
        }

    override suspend fun bootstrap(
        tenantName: String,
        tenantBootstrapClass: Class<*>?,
    ): CodeInjector = this
}

/** Executor factory used by module configs created by [MockExecutorRegistry]. */
class MockExecutorFactory(
    codeInjector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") registry: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    private val mockExecutorRegistry = codeInjector.getProvider(MockExecutorRegistry::class.java).get()

    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: ViaductSchema,
    ): FieldResolverExecutor = mockExecutorRegistry.fieldExecutor(configData.typeName to configData.fieldName)

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: ViaductSchema,
    ): NodeResolverExecutor = mockExecutorRegistry.nodeExecutor(configData.typeName)
}
