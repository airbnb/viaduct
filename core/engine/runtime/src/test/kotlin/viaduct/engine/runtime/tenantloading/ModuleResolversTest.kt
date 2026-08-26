package viaduct.engine.runtime.tenantloading

import io.mockk.mockk
import org.junit.jupiter.api.Test
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.ExecutorFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor

class ModuleResolversTest {
    private val schema = MockSchema.mk(
        """
        type TestType {
            aField: String
        }
        type TestNode implements Node @resolver {
            id: ID!
        }
        extend type Query {
            testField: TestType
        }
        """.trimIndent()
    )

    private val mockFieldExecutor: FieldResolverExecutor = mockk()

    private val mockNodeExecutor: NodeResolverExecutor = mockk()

    private val factory: ExecutorFactory = object : ExecutorFactory {
        override fun createFieldResolverExecutor(
            configData: FieldEntryConfig,
            schema: ViaductSchema
        ) = mockFieldExecutor

        override fun createNodeResolverExecutor(
            configData: NodeEntryConfig,
            schema: ViaductSchema
        ) = mockNodeExecutor
    }

    private fun fieldEntry(
        typeName: String,
        fieldName: String,
    ) = FieldEntryConfig(
        typeName = typeName,
        fieldName = fieldName,
        isBatching = false,
        isSelective = false,
        attribution = "$typeName.$fieldName",
        tenantAPIData = mapOf(
            "resolverClass" to "com.example.Resolver",
            "resolverBaseClass" to "com.example.ResolverBase",
            "queryTypeName" to "Query",
        ),
    )

    private fun nodeEntry(typeName: String) =
        NodeEntryConfig(
            typeName = typeName,
            isBatching = false,
            isSelective = false,
            attribution = typeName,
            tenantAPIData = mapOf(
                "resolverClass" to "com.example.NodeResolver",
                "resolverBaseClass" to "com.example.NodeResolverBase",
            ),
        )

    private fun bootstrapper(registry: ExecutionRegistryConfigFile) =
        ModuleResolvers(
            registry = registry,
            executorFactory = factory,
        )

    @Test
    fun `fieldResolverExecutors - duplicate entries for schema-removed field are silently dropped`() {
        val registry = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = "",
            fields = listOf(
                fieldEntry("RemovedType", "removedField"),
                fieldEntry("RemovedType", "removedField"),
            ),
        )
        val executors = bootstrapper(registry).fieldResolverExecutors(schema).toList()
        assert(executors.isEmpty())
    }

    @Test
    fun `nodeResolverExecutors - duplicate entries for schema-removed node are silently dropped`() {
        val registry = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = "",
            nodes = listOf(
                nodeEntry("RemovedNode"),
                nodeEntry("RemovedNode"),
            ),
        )
        val executors = bootstrapper(registry).nodeResolverExecutors(schema).toList()
        assert(executors.isEmpty())
    }

    @Test
    fun `fieldResolverExecutors - valid entry is delegated to factory`() {
        val registry = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = "",
            fields = listOf(fieldEntry("TestType", "aField")),
        )
        val executors = bootstrapper(registry).fieldResolverExecutors(schema).toList()
        assert(executors.size == 1)
        assert(executors[0].second === mockFieldExecutor)
    }

    @Test
    fun `nodeResolverExecutors - valid entry is delegated to factory`() {
        val registry = ExecutionRegistryConfigFile(
            version = "1",
            executorFactory = "",
            nodes = listOf(nodeEntry("TestNode")),
        )
        val executors = bootstrapper(registry).nodeResolverExecutors(schema).toList()
        assert(executors.size == 1)
        assert(executors[0].second === mockNodeExecutor)
    }
}
