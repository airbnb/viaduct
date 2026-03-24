package viaduct.graphql.schema.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.SchemaWithData
import viaduct.graphql.schema.ViaductSchema

/**
 * Tests for ConstantsDecoder.decodeCompoundConstant using round-trip testing.
 *
 * These tests encode Value objects using ValueStringConverter and ConstantsEncoder,
 * then decode them using ConstantsDecoder.fromFile, verifying the decoded values
 * match the originals.
 *
 * This approach uses the real encoder and decoder implementations, avoiding
 * duplication of encoding/decoding logic in tests.
 */
class CompoundValueDecoderTest {
    /**
     * Helper that encodes constants and provides a decoder for round-trip testing.
     *
     * Uses the real ConstantsEncoder to encode and ConstantsDecoder.fromFile to decode,
     * ensuring we test the actual implementation without duplicating logic.
     */
    private class ConstantsRoundTripper(
        values: List<Any?>,
        identifierNames: List<String> = emptyList()
    ) {
        private val encoder: ConstantsEncoder
        private val decoder: ConstantsDecoder
        private val simpleConstantCount: Int

        init {
            // Build identifier index lookup (must be sorted for binary search compatibility)
            val sortedNames = identifierNames.sorted()
            val identifierIndex: (String) -> Int = { name ->
                sortedNames.binarySearch(name).also {
                    if (it < 0) throw NoSuchElementException("Identifier not found: $name")
                }
            }

            // Build the encoder with all values
            val builder = ConstantsEncoder.Builder()
            values.forEach { builder.addValue(it) }
            encoder = builder.build()
            simpleConstantCount = encoder.simpleConstantsCount

            // Encode to bytes
            val output = ByteArrayOutputStream()
            BOutputStream(output).use { out ->
                encoder.encodeSimpleValues(out)
                encoder.encodeCompoundValues(out, identifierIndex)
            }

            // Build a minimal header with just the info needed for constants decoding
            val header = HeaderSection(
                maxStringLen = 1024,
                identifierCount = 0,
                identifierBytes = 0,
                definitionStubCount = 0,
                sourceLocationCount = 0,
                sourceLocationBytes = 0,
                typeExprSectionBytes = 0,
                typeExprCount = 0,
                directiveCount = 0,
                typeDefCount = 0,
                simpleConstantCount = encoder.simpleConstantsCount,
                simpleConstantBytes = encoder.simpleConstantsBytes,
                compoundConstantCount = encoder.compoundConstantsCount,
                compoundConstantBytes = encoder.compoundConstantsBytes
            )

            // Create decoder using fromFile, which handles all the offset calculation
            val input = ByteArrayInputStream(output.toByteArray())
            BInputStream(input, 1024).use { data ->
                @Suppress("UNCHECKED_CAST")
                val emptyDefs = emptyArray<SchemaWithData.TopLevelDef?>() as Array<SchemaWithData.TopLevelDef>
                val identifierTable = SortedArrayIdentifierTable.fromStrings(sortedNames)
                val identifiers = IdentifiersDecoder(
                    identifierTable,
                    emptyDefs,
                    emptyMap(),
                    emptyMap()
                )
                decoder = ConstantsDecoder.fromFile(data, header, identifiers)
            }
        }

        /**
         * Decode a compound constant by its intermediate representation.
         */
        fun decodeCompound(value: CompoundConstant): ViaductSchema.Literal {
            val constantRef = encoder.findRef(value)
            val compoundIdx = constantRef - simpleConstantCount
            return decoder.decodeCompoundConstant(compoundIdx)
        }
    }

    // ========================================
    // Empty List / Empty Object Tests
    // ========================================

    @Test
    fun `Empty list round-trips correctly`() {
        val emptyList = ListConstant(1, emptyList())
        val tripper = ConstantsRoundTripper(listOf(emptyList))

        val result = tripper.decodeCompound(emptyList)
        assert(result is ViaductSchema.ListLiteral)
        assertEquals(0, (result as ViaductSchema.ListLiteral).size)
    }

    @Test
    fun `Empty object round-trips correctly`() {
        val emptyObj = InputObjectConstant(1, emptyMap())
        val tripper = ConstantsRoundTripper(listOf(emptyObj))

        val result = tripper.decodeCompound(emptyObj)
        assert(result is ViaductSchema.ObjectLiteral)
        assertEquals(0, (result as ViaductSchema.ObjectLiteral).size)
    }

    // ========================================
    // Simple List Tests
    // ========================================

    @Test
    fun `Simple list of integers round-trips correctly`() {
        val arrayValue = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("1"),
                ViaductSchema.IntLiteral.of("2"),
                ViaductSchema.IntLiteral.of("3")
            )
        )
        val converted = ValueStringConverter.valueToString(arrayValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(arrayValue, result)
    }

    @Test
    fun `List with null elements round-trips correctly`() {
        val arrayValue = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("1"),
                ViaductSchema.NULL,
                ViaductSchema.IntLiteral.of("3")
            )
        )
        val converted = ValueStringConverter.valueToString(arrayValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(arrayValue, result)
    }

    @Test
    fun `Single element list round-trips correctly`() {
        val arrayValue = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.IntLiteral.of("42")))
        val converted = ValueStringConverter.valueToString(arrayValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(arrayValue, result)
    }

    // ========================================
    // Nested List Tests
    // ========================================

    @Test
    fun `Nested list of integers round-trips correctly`() {
        val innerArray1 = ViaductSchema.ListLiteral.of(
            listOf(ViaductSchema.IntLiteral.of("1"), ViaductSchema.IntLiteral.of("2"))
        )
        val innerArray2 = ViaductSchema.ListLiteral.of(
            listOf(ViaductSchema.IntLiteral.of("3"), ViaductSchema.IntLiteral.of("4"))
        )
        val outerArray = ViaductSchema.ListLiteral.of(listOf(innerArray1, innerArray2))

        val converted = ValueStringConverter.valueToString(outerArray) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(outerArray, result)
    }

    // ========================================
    // Simple Input Object Tests
    // ========================================

    @Test
    fun `Simple input object round-trips correctly`() {
        val objectValue = ViaductSchema.ObjectLiteral.of(
            mapOf(
                "x" to ViaductSchema.IntLiteral.of("42"),
                "y" to ViaductSchema.StringLiteral.of("hello")
            )
        )
        val converted = ValueStringConverter.valueToString(objectValue) as InputObjectConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("x", "y"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(objectValue, result)
    }

    @Test
    fun `Single field input object round-trips correctly`() {
        val objectValue = ViaductSchema.ObjectLiteral.of(
            mapOf("x" to ViaductSchema.IntLiteral.of("1"))
        )
        val converted = ValueStringConverter.valueToString(objectValue) as InputObjectConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("x"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(objectValue, result)
    }

    // ========================================
    // Nested Input Object Tests
    // ========================================

    @Test
    fun `Nested input object round-trips correctly`() {
        val simpleObjectValue = ViaductSchema.ObjectLiteral.of(
            mapOf(
                "x" to ViaductSchema.IntLiteral.of("10"),
                "y" to ViaductSchema.StringLiteral.of("test")
            )
        )
        val nestedObjectValue = ViaductSchema.ObjectLiteral.of(
            mapOf(
                "simple" to simpleObjectValue,
                "name" to ViaductSchema.StringLiteral.of("nested")
            )
        )
        val converted = ValueStringConverter.valueToString(nestedObjectValue) as InputObjectConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("x", "y", "simple", "name"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(nestedObjectValue, result)
    }

    // ========================================
    // Complex Mixed Tests
    // ========================================

    @Test
    fun `Input object with list field round-trips correctly`() {
        val itemsArray = ViaductSchema.ListLiteral.of(
            listOf(ViaductSchema.IntLiteral.of("1"), ViaductSchema.IntLiteral.of("2"))
        )
        val objectValue = ViaductSchema.ObjectLiteral.of(
            mapOf("items" to itemsArray)
        )
        val converted = ValueStringConverter.valueToString(objectValue) as InputObjectConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("items"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(objectValue, result)
    }

    @Test
    fun `List of input objects round-trips correctly`() {
        val obj1 = ViaductSchema.ObjectLiteral.of(mapOf("name" to ViaductSchema.StringLiteral.of("a")))
        val obj2 = ViaductSchema.ObjectLiteral.of(mapOf("name" to ViaductSchema.StringLiteral.of("b")))
        val listValue = ViaductSchema.ListLiteral.of(listOf(obj1, obj2))

        val converted = ValueStringConverter.valueToString(listValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("name"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(listValue, result)
    }

    @Test
    fun `Boolean values in list round-trip correctly`() {
        val arrayValue = ViaductSchema.ListLiteral.of(
            listOf(ViaductSchema.TRUE, ViaductSchema.FALSE)
        )
        val converted = ValueStringConverter.valueToString(arrayValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(arrayValue, result)
    }

    @Test
    fun `Complex input with lists and objects round-trips correctly`() {
        // 3-level deep structure: outer object contains nested object and list
        val innerObject = ViaductSchema.ObjectLiteral.of(
            mapOf(
                "x" to ViaductSchema.IntLiteral.of("5"),
                "y" to ViaductSchema.StringLiteral.of("inner")
            )
        )

        val nestedObject = ViaductSchema.ObjectLiteral.of(
            mapOf(
                "simple" to innerObject,
                "name" to ViaductSchema.StringLiteral.of("outer")
            )
        )

        val itemsArray = ViaductSchema.ListLiteral.of(
            listOf(
                ViaductSchema.IntLiteral.of("1"),
                ViaductSchema.IntLiteral.of("2")
            )
        )

        val complexObject = ViaductSchema.ObjectLiteral.of(
            mapOf(
                "nested" to nestedObject,
                "items" to itemsArray
            )
        )

        val converted = ValueStringConverter.valueToString(complexObject) as InputObjectConstant

        val tripper = ConstantsRoundTripper(
            listOf(converted),
            listOf("x", "y", "simple", "name", "nested", "items")
        )
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(complexObject, result)
    }

    // ========================================
    // Assertion Helper
    // ========================================

    /**
     * Deep equality check for ViaductSchema Value types.
     */
    private fun assertValuesEqual(
        expected: ViaductSchema.Literal,
        actual: ViaductSchema.Literal
    ) {
        when (expected) {
            is ViaductSchema.NullLiteral -> assert(actual is ViaductSchema.NullLiteral) { "Expected NullValue but got $actual" }
            is ViaductSchema.IntLiteral -> {
                assert(actual is ViaductSchema.IntLiteral) { "Expected IntValue but got $actual" }
                assertEquals(expected.value, (actual as ViaductSchema.IntLiteral).value)
            }
            is ViaductSchema.StringLiteral -> {
                assert(actual is ViaductSchema.StringLiteral) { "Expected StringValue but got $actual" }
                assertEquals(expected.value, (actual as ViaductSchema.StringLiteral).value)
            }
            is ViaductSchema.BooleanLiteral -> {
                assert(actual is ViaductSchema.BooleanLiteral) { "Expected BooleanValue but got $actual" }
                assertEquals(expected.value, (actual as ViaductSchema.BooleanLiteral).value)
            }
            is ViaductSchema.ListLiteral -> {
                assert(actual is ViaductSchema.ListLiteral) { "Expected ListValue but got $actual" }
                val actualList = actual as ViaductSchema.ListLiteral
                assertEquals(expected.size, actualList.size)
                expected.zip(actualList).forEach { (e, a) ->
                    assertValuesEqual(e, a)
                }
            }
            is ViaductSchema.ObjectLiteral -> {
                assert(actual is ViaductSchema.ObjectLiteral) { "Expected ObjectValue but got $actual" }
                val actualObj = actual as ViaductSchema.ObjectLiteral
                assertEquals(expected.size, actualObj.size)
                // Compare by field name since order may vary
                assertEquals(expected.keys, actualObj.keys)
                expected.forEach { (name, expectedValue) ->
                    assertValuesEqual(expectedValue, actualObj[name]!!)
                }
            }
            else -> throw IllegalArgumentException("Unexpected value type: ${expected.javaClass}")
        }
    }
}
