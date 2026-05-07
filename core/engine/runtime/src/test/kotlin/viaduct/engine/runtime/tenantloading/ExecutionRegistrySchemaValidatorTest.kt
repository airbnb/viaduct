package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Test
import viaduct.engine.api.bootstrap.executionregistry.FieldAPIData
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeAPIData
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.mocks.MockSchema

class ExecutionRegistrySchemaValidatorTest {
    private val schema = MockSchema.mk(
        """
        type TestType {
            aField: String
        }
        interface TestInterface {
            id: ID!
        }
        type TestNode implements Node @resolver {
            id: ID!
        }
        extend type Query {
            testField: TestType
        }
        """.trimIndent()
    )

    private fun fieldEntry(
        typeName: String,
        fieldName: String
    ) = FieldEntry(
        typeName = typeName,
        fieldName = fieldName,
        isBatching = false,
        isSelective = false,
        attribution = "$typeName.$fieldName",
        tenantAPIData = FieldAPIData(resolverClass = "com.example.Resolver", resolverBaseClass = "com.example.ResolverBase", queryTypeName = "Query"),
    )

    private fun nodeEntry(typeName: String) =
        NodeEntry(
            typeName = typeName,
            isBatching = false,
            isSelective = false,
            attribution = typeName,
            tenantAPIData = NodeAPIData(resolverClass = "com.example.NodeResolver", resolverBaseClass = "com.example.NodeResolverBase"),
        )

    @Test
    fun `filterFieldsBySchema - known field is kept`() {
        val entry = fieldEntry("TestType", "aField")
        val result = filterFieldsBySchema(listOf(entry), schema)
        assert(result == listOf(entry))
    }

    @Test
    fun `filterFieldsBySchema - unknown field is dropped`() {
        val result = filterFieldsBySchema(listOf(fieldEntry("TestType", "nonExistentField")), schema)
        assert(result.isEmpty())
    }

    @Test
    fun `filterFieldsBySchema - unknown type is dropped`() {
        val result = filterFieldsBySchema(listOf(fieldEntry("GhostType", "someField")), schema)
        assert(result.isEmpty())
    }

    @Test
    fun `filterFieldsBySchema - type exists but is not an object type is dropped`() {
        val result = filterFieldsBySchema(listOf(fieldEntry("TestInterface", "id")), schema)
        assert(result.isEmpty())
    }

    @Test
    fun `filterFieldsBySchema - mixed entries keeps only valid`() {
        val valid = fieldEntry("TestType", "aField")
        val result = filterFieldsBySchema(listOf(valid, fieldEntry("TestType", "missingField")), schema)
        assert(result == listOf(valid))
    }

    @Test
    fun `filterNodesBySchema - node type implementing Node is kept`() {
        val entry = nodeEntry("TestNode")
        val result = filterNodesBySchema(listOf(entry), schema)
        assert(result == listOf(entry))
    }

    @Test
    fun `filterNodesBySchema - unknown type is dropped`() {
        val result = filterNodesBySchema(listOf(nodeEntry("GhostType")), schema)
        assert(result.isEmpty())
    }

    @Test
    fun `filterNodesBySchema - type exists but is not an object type is dropped`() {
        val result = filterNodesBySchema(listOf(nodeEntry("TestInterface")), schema)
        assert(result.isEmpty())
    }

    @Test
    fun `filterNodesBySchema - object type not implementing Node is dropped`() {
        val result = filterNodesBySchema(listOf(nodeEntry("TestType")), schema)
        assert(result.isEmpty())
    }

    @Test
    fun `filterNodesBySchema - mixed entries keeps only valid`() {
        val valid = nodeEntry("TestNode")
        val result = filterNodesBySchema(listOf(valid, nodeEntry("TestType")), schema)
        assert(result == listOf(valid))
    }
}
