package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.spi.TenantModuleException

class ExecutionRegistryValidatorTest {
    private fun fieldEntry(
        typeName: String,
        fieldName: String,
        resolverClass: String = "com.example.Resolver"
    ) = FieldEntryConfig(
        typeName = typeName,
        fieldName = fieldName,
        isBatching = false,
        isSelective = false,
        attribution = "$typeName.$fieldName",
        tenantAPIData = mapOf("resolverClass" to resolverClass, "resolverBaseClass" to "com.example.ResolverBase", "queryTypeName" to "Query"),
    )

    private fun nodeEntry(
        typeName: String,
        resolverClass: String = "com.example.NodeResolver",
        isBatching: Boolean = false
    ) = NodeEntryConfig(
        typeName = typeName,
        isBatching = isBatching,
        isSelective = false,
        attribution = typeName,
        tenantAPIData = mapOf("resolverClass" to resolverClass, "resolverBaseClass" to "com.example.NodeResolverBase"),
    )

    private fun registry(
        fields: List<FieldEntryConfig> = emptyList(),
        nodes: List<NodeEntryConfig> = emptyList()
    ) = ExecutionRegistryConfigFile(version = "1", executorFactory = "", fields = fields, nodes = nodes)

    @Test
    fun `no duplicates passes`() {
        assertDoesNotThrow {
            validateSchemaFree(registry(fields = listOf(fieldEntry("Query", "a"), fieldEntry("Query", "b"))))
        }
    }

    @Test
    fun `duplicate field coordinate throws TenantModuleException`() {
        val ex = assertThrows<TenantModuleException> {
            validateSchemaFree(registry(fields = listOf(fieldEntry("Query", "a"), fieldEntry("Query", "a"))))
        }
        assert(ex.message!!.contains("Duplicate resolver for type Query and field a"))
    }

    @Test
    fun `duplicate field coordinate message includes resolver class`() {
        val ex = assertThrows<TenantModuleException> {
            validateSchemaFree(
                registry(
                    fields = listOf(
                        fieldEntry("Query", "a", "com.example.Resolver"),
                        fieldEntry("Query", "a", "com.example.OtherResolver"),
                    )
                )
            )
        }
        assert(ex.message!!.contains("com.example.Resolver"))
        assert(ex.message!!.contains("com.example.OtherResolver"))
    }

    @Test
    fun `same field name on different types is allowed`() {
        assertDoesNotThrow {
            validateSchemaFree(registry(fields = listOf(fieldEntry("TypeA", "x"), fieldEntry("TypeB", "x"))))
        }
    }

    @Test
    fun `no duplicate nodes passes`() {
        assertDoesNotThrow {
            validateSchemaFree(registry(nodes = listOf(nodeEntry("TypeA"), nodeEntry("TypeB"))))
        }
    }

    @Test
    fun `duplicate node typeName throws TenantModuleException`() {
        val ex = assertThrows<TenantModuleException> {
            validateSchemaFree(registry(nodes = listOf(nodeEntry("TestNode"), nodeEntry("TestNode"))))
        }
        assert(ex.message!!.contains("Duplicate node resolver for type TestNode"))
    }

    @Test
    fun `duplicate node message includes resolver class`() {
        val ex = assertThrows<TenantModuleException> {
            validateSchemaFree(
                registry(
                    nodes = listOf(
                        nodeEntry("TestNode", "com.example.NodeResolver"),
                        nodeEntry("TestNode", "com.example.OtherNodeResolver"),
                    )
                )
            )
        }
        assert(ex.message!!.contains("com.example.NodeResolver"))
        assert(ex.message!!.contains("com.example.OtherNodeResolver"))
    }

    @Test
    fun `duplicate batch node typeName throws TenantModuleException`() {
        assertThrows<TenantModuleException> {
            validateSchemaFree(
                registry(
                    nodes = listOf(
                        nodeEntry("TestNode", isBatching = true),
                        nodeEntry("TestNode", isBatching = true),
                    )
                )
            )
        }
    }

    @Test
    fun `mixed batch and non-batch for same node typeName throws TenantModuleException`() {
        assertThrows<TenantModuleException> {
            validateSchemaFree(
                registry(
                    nodes = listOf(
                        nodeEntry("TestNode", isBatching = false),
                        nodeEntry("TestNode", isBatching = true),
                    )
                )
            )
        }
    }
}
