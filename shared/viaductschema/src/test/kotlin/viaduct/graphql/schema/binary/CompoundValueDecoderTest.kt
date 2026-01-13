package viaduct.graphql.schema.binary

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
                val emptyDefs = emptyArray<BSchema.TopLevelDef?>() as Array<BSchema.TopLevelDef>
                val identifierTable = SortedArrayIdentifierTable.fromStrings(sortedNames)
                val identifiers = IdentifiersDecoder(identifierTable, emptyDefs)
                decoder = ConstantsDecoder.fromFile(data, header, identifiers)
            }
        }

        /**
         * Decode a compound constant by its intermediate representation.
         */
        fun decodeCompound(value: CompoundConstant): Value<*> {
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
        assert(result is ArrayValue)
        assertEquals(0, (result as ArrayValue).values.size)
    }

    @Test
    fun `Empty object round-trips correctly`() {
        val emptyObj = InputObjectConstant(1, emptyMap())
        val tripper = ConstantsRoundTripper(listOf(emptyObj))

        val result = tripper.decodeCompound(emptyObj)
        assert(result is ObjectValue)
        assertEquals(0, (result as ObjectValue).objectFields.size)
    }

    // ========================================
    // Simple List Tests
    // ========================================

    @Test
    fun `Simple list of integers round-trips correctly`() {
        val arrayValue = ArrayValue(
            listOf(
                IntValue(BigInteger("1")),
                IntValue(BigInteger("2")),
                IntValue(BigInteger("3"))
            )
        )
        val converted = ValueStringConverter.valueToString(arrayValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(arrayValue, result)
    }

    @Test
    fun `List with null elements round-trips correctly`() {
        val arrayValue = ArrayValue(
            listOf(
                IntValue(BigInteger("1")),
                NullValue.newNullValue().build(),
                IntValue(BigInteger("3"))
            )
        )
        val converted = ValueStringConverter.valueToString(arrayValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(arrayValue, result)
    }

    @Test
    fun `Single element list round-trips correctly`() {
        val arrayValue = ArrayValue(listOf(IntValue(BigInteger("42"))))
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
        val innerArray1 = ArrayValue(
            listOf(IntValue(BigInteger("1")), IntValue(BigInteger("2")))
        )
        val innerArray2 = ArrayValue(
            listOf(IntValue(BigInteger("3")), IntValue(BigInteger("4")))
        )
        val outerArray = ArrayValue(listOf(innerArray1, innerArray2))

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
        val objectValue = ObjectValue(
            listOf(
                ObjectField("x", IntValue(BigInteger("42"))),
                ObjectField("y", StringValue("hello"))
            )
        )
        val converted = ValueStringConverter.valueToString(objectValue) as InputObjectConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("x", "y"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(objectValue, result)
    }

    @Test
    fun `Single field input object round-trips correctly`() {
        val objectValue = ObjectValue(
            listOf(ObjectField("x", IntValue(BigInteger("1"))))
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
        val simpleObjectValue = ObjectValue(
            listOf(
                ObjectField("x", IntValue(BigInteger("10"))),
                ObjectField("y", StringValue("test"))
            )
        )
        val nestedObjectValue = ObjectValue(
            listOf(
                ObjectField("simple", simpleObjectValue),
                ObjectField("name", StringValue("nested"))
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
        val itemsArray = ArrayValue(
            listOf(IntValue(BigInteger("1")), IntValue(BigInteger("2")))
        )
        val objectValue = ObjectValue(
            listOf(ObjectField("items", itemsArray))
        )
        val converted = ValueStringConverter.valueToString(objectValue) as InputObjectConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("items"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(objectValue, result)
    }

    @Test
    fun `List of input objects round-trips correctly`() {
        val obj1 = ObjectValue(listOf(ObjectField("name", StringValue("a"))))
        val obj2 = ObjectValue(listOf(ObjectField("name", StringValue("b"))))
        val listValue = ArrayValue(listOf(obj1, obj2))

        val converted = ValueStringConverter.valueToString(listValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted), listOf("name"))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(listValue, result)
    }

    @Test
    fun `Boolean values in list round-trip correctly`() {
        val arrayValue = ArrayValue(
            listOf(BooleanValue(true), BooleanValue(false))
        )
        val converted = ValueStringConverter.valueToString(arrayValue) as ListConstant

        val tripper = ConstantsRoundTripper(listOf(converted))
        val result = tripper.decodeCompound(converted)

        assertValuesEqual(arrayValue, result)
    }

    @Test
    fun `Complex input with lists and objects round-trips correctly`() {
        // 3-level deep structure: outer object contains nested object and list
        val innerObject = ObjectValue(
            listOf(
                ObjectField("x", IntValue(BigInteger("5"))),
                ObjectField("y", StringValue("inner"))
            )
        )

        val nestedObject = ObjectValue(
            listOf(
                ObjectField("simple", innerObject),
                ObjectField("name", StringValue("outer"))
            )
        )

        val itemsArray = ArrayValue(
            listOf(
                IntValue(BigInteger("1")),
                IntValue(BigInteger("2"))
            )
        )

        val complexObject = ObjectValue(
            listOf(
                ObjectField("nested", nestedObject),
                ObjectField("items", itemsArray)
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
     * Deep equality check for GraphQL Value types.
     */
    private fun assertValuesEqual(
        expected: Value<*>,
        actual: Value<*>
    ) {
        when (expected) {
            is NullValue -> assert(actual is NullValue) { "Expected NullValue but got $actual" }
            is IntValue -> {
                assert(actual is IntValue) { "Expected IntValue but got $actual" }
                assertEquals(expected.value, (actual as IntValue).value)
            }
            is StringValue -> {
                assert(actual is StringValue) { "Expected StringValue but got $actual" }
                assertEquals(expected.value, (actual as StringValue).value)
            }
            is BooleanValue -> {
                assert(actual is BooleanValue) { "Expected BooleanValue but got $actual" }
                assertEquals(expected.isValue, (actual as BooleanValue).isValue)
            }
            is ArrayValue -> {
                assert(actual is ArrayValue) { "Expected ArrayValue but got $actual" }
                val actualArray = actual as ArrayValue
                assertEquals(expected.values.size, actualArray.values.size)
                expected.values.zip(actualArray.values).forEach { (e, a) ->
                    assertValuesEqual(e, a)
                }
            }
            is ObjectValue -> {
                assert(actual is ObjectValue) { "Expected ObjectValue but got $actual" }
                val actualObj = actual as ObjectValue
                assertEquals(expected.objectFields.size, actualObj.objectFields.size)
                // Compare by field name since order may vary
                val expectedFields = expected.objectFields.associateBy { it.name }
                val actualFields = actualObj.objectFields.associateBy { it.name }
                assertEquals(expectedFields.keys, actualFields.keys)
                expectedFields.forEach { (name, expectedField) ->
                    assertValuesEqual(expectedField.value, actualFields[name]!!.value)
                }
            }
            else -> throw IllegalArgumentException("Unexpected value type: ${expected.javaClass}")
        }
    }
}
