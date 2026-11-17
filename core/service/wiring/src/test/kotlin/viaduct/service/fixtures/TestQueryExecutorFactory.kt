package viaduct.service.fixtures

import viaduct.bootstrap.ExecutionRegistryConfigFile
import viaduct.bootstrap.FieldEntryConfig
import viaduct.bootstrap.NodeEntryConfig
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector

class TestQueryExecutorFactory(
    @Suppress("UNUSED_PARAMETER") injector: CodeInjector,
    @Suppress("UNUSED_PARAMETER") config: ExecutionRegistryConfigFile,
) : ExecutorFactory {
    override fun createFieldResolverExecutor(
        configData: FieldEntryConfig,
        schema: EngineSchema
    ): FieldResolverExecutor =
        MockFieldUnbatchedResolverExecutor(resolverId = "${configData.typeName}.${configData.fieldName}") { _, _, _, _, _ ->
            if (configData.fieldName == "greeting") "Hello from the classpath resolver" else null
        }

    override fun createNodeResolverExecutor(
        configData: NodeEntryConfig,
        schema: EngineSchema
    ): NodeResolverExecutor = error("This fixture supplies only Query field resolvers")
}
