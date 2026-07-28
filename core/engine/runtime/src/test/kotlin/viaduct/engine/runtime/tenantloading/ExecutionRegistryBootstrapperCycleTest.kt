package viaduct.engine.runtime.tenantloading

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.mocks.MockCheckerExecutorFactory
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.MockTenantAPIBootstrapper
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor

@ExperimentalCoroutinesApi
class ExecutionRegistryBootstrapperCycleTest {
    private val schema = MockSchema.mk(
        """
        extend type Query {
            foo: String
            bar: String
        }
        """.trimIndent()
    )

    private fun fieldExecutorWithObjectSelections(
        typeName: String,
        fieldName: String,
        selections: String
    ): FieldResolverExecutor {
        val rss = RequiredSelectionSet(
            selections = SelectionsParser.parse(typeName, "fragment _ on $typeName { $selections }"),
            variablesResolvers = emptyList(),
            forChecker = false,
        )
        return object : FieldResolverExecutor {
            override val objectSelectionSet = rss
            override val querySelectionSet: RequiredSelectionSet? = null
            override val isSelective = false
            override val resolverId = "$typeName.$fieldName"
            override val metadata = ResolverMetadata.forMock("$typeName.$fieldName")
            override val isBatching = false

            override suspend fun batchResolve(
                selectors: List<FieldResolverExecutor.Selector>,
                context: EngineExecutionContext,
            ): Map<FieldResolverExecutor.Selector, Result<Any?>> = emptyMap()
        }
    }

    private fun bootstrapper(registry: ExecutionRegistryConfigFile): ExecutionRegistryTenantModuleBootstrapper {
        val fooExecutor = fieldExecutorWithObjectSelections("Query", "foo", "bar")
        val barExecutor = fieldExecutorWithObjectSelections("Query", "bar", "foo")
        val factory = object : ExecutorFactory {
            override fun createFieldResolverExecutor(
                configData: FieldEntryConfig,
                schema: ViaductSchema
            ): FieldResolverExecutor = if (configData.fieldName == "foo") fooExecutor else barExecutor

            override fun createNodeResolverExecutor(
                configData: NodeEntryConfig,
                schema: ViaductSchema
            ): NodeResolverExecutor = throw UnsupportedOperationException()
        }
        return ExecutionRegistryTenantModuleBootstrapper(registry = registry, executorFactory = factory)
    }

    private fun fieldEntry(fieldName: String) =
        FieldEntryConfig(
            typeName = "Query",
            fieldName = fieldName,
            isBatching = false,
            isSelective = false,
            attribution = "Query.$fieldName",
            tenantAPIData = mapOf(
                "resolverClass" to "com.example.Resolver",
                "resolverBaseClass" to "com.example.ResolverBase",
                "queryTypeName" to "Query",
            ),
        )

    @Test
    fun `cycle detection fires through file-based bootstrapping`() {
        val registry = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = "",
            fields = listOf(fieldEntry("foo"), fieldEntry("bar")),
        )
        assertThrows<RequiredSelectionsCycleException> {
            TenantAPIBootstrapperDispatcherRegistryFactory(
                tenantAPIBootstrapper = MockTenantAPIBootstrapper(listOf(bootstrapper(registry))),
                validator = ExecutorValidator(schema),
                checkerExecutorFactory = MockCheckerExecutorFactory(),
            ).create(schema)
        }
    }
}
