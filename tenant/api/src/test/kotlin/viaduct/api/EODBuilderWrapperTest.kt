package viaduct.api

import graphql.schema.GraphQLObjectType
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.EODBuilderWrapper
import viaduct.api.mocks.mkSchema
import viaduct.engine.api.EngineObjectData
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * Test suite for EODBuilderWrapper with emphasis on graceful handling of schema version skew.
 *
 * This suite verifies EODBuilderWrapper's type handling behavior during schema evolution and
 * version skew scenarios that can occur during hotswap/MTD deployments.  Tests cover proper
 * handling of various GraphQL types, validation of input values, and tolerance for runtime
 * schema changes.
 */
class EODBuilderWrapperTest {
    enum class TestEnum {
        VALUE_A,
        VALUE_B
    }

    private val testSchema = mkSchema(
        """
        type Query {
            placeholder: String
        }

        type TestObject {
            enumField: TestEnum
            stringField: String
            intField: Int
        }

        enum TestEnum {
            VALUE_A
            VALUE_B
            VALUE_C
        }
        """.trimIndent()
    )

    private val testObjectType = testSchema.getType("TestObject") as GraphQLObjectType
    private val globalIDCodec = GlobalIDCodecDefault

    @Test
    fun `unwrapEnum handles Java enum instance`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        // Put a Java enum value - should be unwrapped to string name
        wrapper.put("enumField", TestEnum.VALUE_A)

        val result = wrapper.getEngineObjectData()
        assertEquals("VALUE_A", (result as EngineObjectData.Sync).get("enumField"))
    }

    @Test
    fun `unwrapEnum handles string value for schema version skew`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        // During version skew, runtime schema may have VALUE_C but compiled enum doesn't
        // Passing string "VALUE_C" should work (it exists in schema but not in TestEnum)
        wrapper.put("enumField", "VALUE_C")

        val result = wrapper.getEngineObjectData()
        assertEquals("VALUE_C", (result as EngineObjectData.Sync).get("enumField"))
    }

    @Test
    fun `unwrapEnum handles string value matching compiled enum`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        // String value that happens to match compiled enum should also work
        wrapper.put("enumField", "VALUE_A")

        val result = wrapper.getEngineObjectData()
        assertEquals("VALUE_A", (result as EngineObjectData.Sync).get("enumField"))
    }

    @Test
    fun `unwrapEnum rejects integer value`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        val e = assertThrows<IllegalArgumentException> {
            wrapper.put("enumField", 42)
        }
        assertEquals("Got non-enum value 42 for enum type (expected Enum or String)", e.message)
    }

    @Test
    fun `unwrapEnum rejects null for non-null enum field`() {
        // Create schema with non-null enum field
        val schemaWithNonNull = mkSchema(
            """
            type Query {
                placeholder: String
            }

            type TestObject {
                enumField: TestEnum!
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()
        )
        val objectType = schemaWithNonNull.getType("TestObject") as GraphQLObjectType
        val wrapper = EODBuilderWrapper(objectType, globalIDCodec)

        val e = assertThrows<IllegalArgumentException> {
            wrapper.put("enumField", null)
        }
        assertEquals("Got null builder value for non-null type TestEnum!", e.message)
    }

    @Test
    fun `unwrapEnum handles null for nullable enum field`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        // Nullable enum field should accept null
        wrapper.put("enumField", null)

        val result = wrapper.getEngineObjectData()
        assertEquals(null, (result as EngineObjectData.Sync).get("enumField"))
    }

    @Test
    fun `unwrapEnum rejects boolean value`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        val e = assertThrows<IllegalArgumentException> {
            wrapper.put("enumField", true)
        }
        assertEquals("Got non-enum value true for enum type (expected Enum or String)", e.message)
    }

    @Test
    fun `unwrapEnum rejects list value`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        val e = assertThrows<IllegalArgumentException> {
            wrapper.put("enumField", listOf("VALUE_A"))
        }
        assertEquals("Got non-enum value [VALUE_A] for enum type (expected Enum or String)", e.message)
    }

    @Test
    fun `unwrapEnum rejects map value`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        val e = assertThrows<IllegalArgumentException> {
            wrapper.put("enumField", mapOf("value" to "VALUE_A"))
        }
        assertEquals("Got non-enum value {value=VALUE_A} for enum type (expected Enum or String)", e.message)
    }

    @Test
    fun `unwrapEnum handles list of enum values`() {
        val schemaWithList = mkSchema(
            """
            type Query {
                placeholder: String
            }

            type TestObject {
                enumList: [TestEnum]
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
                VALUE_C
            }
            """.trimIndent()
        )
        val objectType = schemaWithList.getType("TestObject") as GraphQLObjectType
        val wrapper = EODBuilderWrapper(objectType, globalIDCodec)

        // List with mix of Java enum and string (for version skew)
        wrapper.put("enumList", listOf(TestEnum.VALUE_A, "VALUE_B", "VALUE_C"))

        val result = wrapper.getEngineObjectData()
        assertEquals(listOf("VALUE_A", "VALUE_B", "VALUE_C"), (result as EngineObjectData.Sync).get("enumList"))
    }

    @Test
    fun `unwrapEnum handles list with null values`() {
        val schemaWithList = mkSchema(
            """
            type Query {
                placeholder: String
            }

            type TestObject {
                enumList: [TestEnum]
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()
        )
        val objectType = schemaWithList.getType("TestObject") as GraphQLObjectType
        val wrapper = EODBuilderWrapper(objectType, globalIDCodec)

        wrapper.put("enumList", listOf(TestEnum.VALUE_A, null, "VALUE_B"))

        val result = wrapper.getEngineObjectData()
        assertEquals(listOf("VALUE_A", null, "VALUE_B"), (result as EngineObjectData.Sync).get("enumList"))
    }

    @Test
    fun `unwrapEnum rejects invalid type in list`() {
        val schemaWithList = mkSchema(
            """
            type Query {
                placeholder: String
            }

            type TestObject {
                enumList: [TestEnum]
            }

            enum TestEnum {
                VALUE_A
                VALUE_B
            }
            """.trimIndent()
        )
        val objectType = schemaWithList.getType("TestObject") as GraphQLObjectType
        val wrapper = EODBuilderWrapper(objectType, globalIDCodec)

        val e = assertThrows<IllegalArgumentException> {
            wrapper.put("enumList", listOf(TestEnum.VALUE_A, 42, "VALUE_B"))
        }
        assertEquals("Got non-enum value 42 for enum type (expected Enum or String)", e.message)
    }

    @Test
    fun `unwrapEnum handles empty string`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        // Empty string is still a string, should pass through
        wrapper.put("enumField", "")

        val result = wrapper.getEngineObjectData()
        assertEquals("", (result as EngineObjectData.Sync).get("enumField"))
    }

    @Test
    @Suppress("USELESS_CAST")
    fun `put handles multiple fields including enum`() {
        val wrapper = EODBuilderWrapper(testObjectType, globalIDCodec)

        wrapper.put("enumField", TestEnum.VALUE_B)
        wrapper.put("stringField", "test")
        wrapper.put("intField", 123)

        val result = wrapper.getEngineObjectData()
        assertEquals("VALUE_B", (result as EngineObjectData.Sync).get("enumField"))
        assertEquals("test", (result as EngineObjectData.Sync).get("stringField"))
        assertEquals(123, (result as EngineObjectData.Sync).get("intField"))
    }
}
