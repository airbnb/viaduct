package viaduct.engine.api.mocks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.UnsetSelectionException

class MkEngineObjectDataTest {
    val schema = MockSchema.mk(
        """
        extend type Query {
            test: Test
        }
        type Test {
            string: String
            int: Int
            listInt: [Int]
            nested: Nested
            listNested: [Nested]
        }
        type Nested {
            int: Int
            listInt: [Int]
            leaf: Leaf
            leafNested: [Leaf]
        }
        type Leaf {
            int: Int
        }
        """.trimIndent()
    )

    private fun type(name: String) = schema.schema.getObjectType(name)

    @Test
    fun `empty top-level map`() {
        val data = emptyMap<String, Any?>()
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `single null field`() {
        val data = mapOf<String, Any?>("string" to null)
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `single non-null integer field`() {
        val data = mapOf<String, Any?>("int" to 42)
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `null and non-null integer field`() {
        val data = mapOf<String, Any?>("string" to "hello", "int" to null)
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `single field that's an empty list`() {
        val data = mapOf<String, Any?>("listInt" to emptyList<Int>())
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `single field that's a list of mixed nulls and non-nulls`() {
        val data = mapOf<String, Any?>("listInt" to listOf(1, null, 2, null, 3))
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `nested field with no subfield set`() {
        val data = mapOf<String, Any?>("nested" to emptyMap<String, Any?>())
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `nested field with all test cases`() {
        val nestedData = mapOf<String, Any?>(
            "int" to 42,
            "listInt" to listOf(1, null, 2)
        )
        val data = mapOf<String, Any?>("nested" to nestedData)
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `listNested with a mix of nulls and Nested`() {
        val nestedData1 = mapOf<String, Any?>("int" to 1)
        val nestedData2 = mapOf<String, Any?>("int" to 2, "listInt" to listOf(10, 20))
        val data = mapOf<String, Any?>("listNested" to listOf(nestedData1, null, nestedData2))
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `nested field with a leaf field`() {
        val leafData = mapOf<String, Any?>("int" to 99)
        val nestedData = mapOf<String, Any?>("leaf" to leafData)
        val data = mapOf<String, Any?>("nested" to nestedData)
        val result = mkEngineObjectData(type("Test"), data)
        test(data, result)
    }

    @Test
    fun `accessing unset field fails`() {
        val data = mapOf<String, Any?>("int" to 1)
        val result = mkEngineObjectData(type("Test"), data)
        assertThrows<UnsetSelectionException> {
            result.fetchSync("foo")
        }
    }

    @Test
    fun `accessing unset field in nested object fails`() {
        val data = mapOf<String, Any?>("nested" to emptyMap<String, Any?>())
        val result = mkEngineObjectData(type("Test"), data)
        assertThrows<UnsetSelectionException> {
            (result.fetchSync("nested") as ResolvedEngineObjectData).fetchSync("unsetField")
        }
    }

    @Test
    fun `accessing unset field in leaf object fails`() {
        val nestedData = mapOf<String, Any?>("leaf" to emptyMap<String, Any?>())
        val data = mapOf<String, Any?>("nested" to nestedData)
        val result = mkEngineObjectData(type("Test"), data)
        assertThrows<UnsetSelectionException> {
            ((result.fetchSync("nested") as ResolvedEngineObjectData).fetchSync("leaf") as ResolvedEngineObjectData).fetchSync("nonExistentField")
        }
    }

    private fun test(
        expected: Any?,
        actual: Any?
    ) {
        if (expected == null) {
            assertNull(actual)
            return
        }
        assertNotNull(actual)
        actual!!
        when (expected) {
            is List<*> -> {
                assertInstanceOf(List::class.java, actual)
                assertEquals(expected.size, (actual as List<*>).size)
                expected.zip(actual) { e, a -> test(e, a) }
            }
            is EngineObjectData -> throw IllegalArgumentException("Expected values can't be EODs ($expected).")
            is Map<*, *> -> {
                assertInstanceOf(ResolvedEngineObjectData::class.java, actual)
                actual as ResolvedEngineObjectData
                val selectionsThatAreSet = actual.data.keys
                assertEquals(expected.keys, selectionsThatAreSet)
                expected.forEach { (key, value) -> test(value, actual.fetchSync(key as String)) }
            }
            else -> assertEquals(expected, actual)
        }
    }
}
