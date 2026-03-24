package viaduct.graphql.schema.binary

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/**
 * Tests for ValueStringConverter using round-trip testing.
 *
 * Round-trip test: String -> Value -> String and verify the result matches the original.
 */
class ValueStringConverterTest {
    private val testSchema = """
        $builtins

        scalar Byte
        scalar Short
        scalar Long
        scalar Date
        scalar DateTime
        scalar Time
        scalar Json
        scalar BackingData

        enum Color {
            RED
            GREEN
            BLUE
        }

        enum Status {
            ACTIVE
            INACTIVE
            PENDING
        }

        input TestInput {
            field: String
        }

        input SimpleInput {
            x: Int!
            y: String
        }

        input NestedInput {
            simple: SimpleInput!
            name: String!
        }

        input ComplexInput {
            nested: NestedInput
            items: [Int!]
            optionalItems: [String]
        }
    """.trimIndent()

    private val schema by lazy {
        val tdr = SchemaParser().parse(testSchema)
        ViaductSchema.fromTypeDefinitionRegistry(tdr)
    }

    /**
     * Helper function to perform round-trip test with kind-code-prefixed strings.
     *
     * Tests: kind-code-prefixed String -> Value -> kind-code-prefixed String
     *
     * @param contentStr The content string (without kind code)
     * @param expectedResult If provided, expect this content after round-trip (for normalization)
     */
    private fun assertRoundTrip(
        typeName: String,
        contentStr: String,
        expectedResult: String? = null
    ) {
        val typeDef = schema.types[typeName]
            ?: throw IllegalArgumentException("Type $typeName not found in schema")

        // Determine the kind code for this type
        val kindCode = when (typeDef) {
            is ViaductSchema.Enum -> K_ENUM_VALUE
            is ViaductSchema.Scalar -> when (typeDef.name) {
                "Boolean" -> K_BOOLEAN_VALUE
                "Byte", "Short", "Int", "Long" -> K_INT_VALUE
                "Float" -> K_FLOAT_VALUE
                else -> K_STRING_VALUE
            }
            else -> throw IllegalArgumentException("Unexpected type: $typeDef")
        }

        // Create the kind-code-prefixed input string
        val inputEncodedStr = "${kindCode.toChar()}$contentStr"

        // kind-code-prefixed String -> Value
        val value = ValueStringConverter.stringToSimpleValue(inputEncodedStr)

        // Value -> kind-code-prefixed String
        val outputEncodedStr = ValueStringConverter.simpleValueToString(value)

        // Expected result (with kind code)
        val expectedContent = expectedResult ?: contentStr
        val expectedEncodedStr = "${kindCode.toChar()}$expectedContent"

        // Verify round-trip
        assertEquals(expectedEncodedStr, outputEncodedStr)
    }

    @ParameterizedTest
    @ValueSource(strings = ["true", "false"])
    fun `Boolean round-trip`(value: String) {
        assertRoundTrip("Boolean", value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["42", "-123", "0", "9223372036854775807", "123456789012345678901234567890"])
    fun `Int round-trip`(value: String) {
        assertRoundTrip("Int", value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["127", "-128"])
    fun `Byte round-trip`(value: String) {
        assertRoundTrip("Byte", value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["32767", "-32768"])
    fun `Short round-trip`(value: String) {
        assertRoundTrip("Short", value)
    }

    @Test
    fun `Long - positive from IntValue`() {
        // Long can be represented as IntValue in literals
        val value = ViaductSchema.IntLiteral.of("9223372036854775807")
        val result = ValueStringConverter.simpleValueToString(value)
        assertEquals("${K_INT_VALUE.toChar()}9223372036854775807", result)
    }

    @Test
    fun `Long - from StringValue`() {
        assertRoundTrip("Long", "9223372036854775807")
    }

    @Test
    fun `Long - negative`() {
        assertRoundTrip("Long", "-9223372036854775808")
    }

    @ParameterizedTest
    @CsvSource(
        "3.14, 3.14",
        "-2.5, -2.5",
        "1.5E10, 1.5E10", // ViaductSchema.FloatLiteral preserves literal as-is
        "2.5E-3, 2.5E-3", // ViaductSchema.FloatLiteral preserves literal as-is
        "0.0, 0.0",
        "42.0, 42.0" // GraphQL float literals must have decimal point or exponent
    )
    fun `Float round-trip`(
        input: String,
        expected: String
    ) {
        assertRoundTrip("Float", input, expectedResult = expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "hello",
            "",
            "hello world",
            "hello\nworld\ttab",
            "she said \"hello\"",
            "こんにちは世界",
            "🎉🚀",
            "null" // The literal word "null" treated as regular string
        ]
    )
    fun `String round-trip`(value: String) {
        assertRoundTrip("String", value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "user123",
            "42",
            "550e8400-e29b-41d4-a716-446655440000"
        ]
    )
    fun `ID round-trip`(value: String) {
        assertRoundTrip("ID", value)
    }

    @ParameterizedTest
    @CsvSource(
        "Date, 2024-01-15",
        "DateTime, 2024-01-15T10:30:00Z",
        "Time, 10:30:00+00:00"
    )
    fun `Date time scalars round-trip`(
        typeName: String,
        value: String
    ) {
        assertRoundTrip(typeName, value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"key\":\"value\"}",
            "[1,2,3]",
            "{\"users\":[{\"name\":\"Alice\",\"age\":30}]}"
        ]
    )
    fun `Json round-trip`(value: String) {
        assertRoundTrip("Json", value)
    }

    @ParameterizedTest
    @CsvSource(
        "Color, RED",
        "Color, GREEN",
        "Color, BLUE",
        "Status, ACTIVE",
        "Status, INACTIVE",
        "Status, PENDING"
    )
    fun `Enum round-trip`(
        typeName: String,
        value: String
    ) {
        assertRoundTrip(typeName, value)
    }

    @Test
    fun `Error - invalid boolean value`() {
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("True")
        }
    }

    @Test
    fun `Error - invalid integer value`() {
        // Use correct kind code prefix with invalid content
        val ki = K_INT_VALUE.toChar()
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("${ki}not-a-number")
        }
    }

    @Test
    fun `Error - invalid float value`() {
        // Use correct kind code prefix with invalid content
        val kf = K_FLOAT_VALUE.toChar()
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("${kf}not-a-float")
        }
    }

    @Test
    fun `Error - empty string`() {
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("")
        }
    }

    @Test
    fun `Error - stringToSimpleValue with unknown kind code should fail`() {
        assertThrows<IllegalArgumentException> {
            // 'X' is not a valid kind code
            ValueStringConverter.stringToSimpleValue("Xtest")
        }
    }

    @Test
    fun `EnumValue encodes with kind code`() {
        val value = ViaductSchema.EnumLit.of("VALUE")
        val result = ValueStringConverter.simpleValueToString(value)
        assertEquals("${K_ENUM_VALUE.toChar()}VALUE", result)
    }

    @Test
    fun `Error - Unsupported Value type (ListValue)`() {
        val listValue = ViaductSchema.ListLiteral.of(listOf(ViaductSchema.StringLiteral.of("test")))
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.simpleValueToString(listValue)
        }
    }

    @Test
    fun `Error - Unsupported Value type (ObjectValue)`() {
        val objectValue = ViaductSchema.ObjectLiteral.of(emptyMap())
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.simpleValueToString(objectValue)
        }
    }

    @Test
    fun `BooleanValue encodes with kind code`() {
        val result = ValueStringConverter.simpleValueToString(ViaductSchema.TRUE)
        assertEquals("${K_BOOLEAN_VALUE.toChar()}true", result)
    }

    @Test
    fun `IntValue encodes with kind code`() {
        val value = ViaductSchema.IntLiteral.of("42")
        val result = ValueStringConverter.simpleValueToString(value)
        assertEquals("${K_INT_VALUE.toChar()}42", result)
    }

    @Test
    fun `FloatValue encodes with kind code`() {
        val value = ViaductSchema.FloatLiteral.of("3.14")
        val result = ValueStringConverter.simpleValueToString(value)
        assertEquals("${K_FLOAT_VALUE.toChar()}3.14", result)
    }

    @Test
    fun `StringValue encodes with kind code`() {
        val value = ViaductSchema.StringLiteral.of("true")
        val result = ValueStringConverter.simpleValueToString(value)
        assertEquals("${K_STRING_VALUE.toChar()}true", result)
    }

    @Test
    fun `Boolean - parse true explicitly`() {
        // This ensures we hit the "true" branch in the when expression
        assertRoundTrip("Boolean", "true")
    }

    @Test
    fun `Error - invalid boolean string format`() {
        // Note: These all fail because they don't have the boolean kind code prefix 'P'
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("TRUE")
        }

        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("yes")
        }

        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("1")
        }

        // With correct kind code but invalid content
        assertThrows<IllegalArgumentException> {
            ValueStringConverter.stringToSimpleValue("PTRUE") // Should be "Ptrue"
        }
    }

    @Test
    fun `String kind code decodes to StringValue`() {
        val value = ValueStringConverter.stringToSimpleValue("${K_STRING_VALUE.toChar()}some-value")
        assertEquals(ViaductSchema.StringLiteral::class.java, value.javaClass)
        assertEquals("some-value", (value as ViaductSchema.StringLiteral).value)
    }

    @Test
    fun `StringValue with empty content`() {
        val value = ViaductSchema.StringLiteral.of("")
        val result = ValueStringConverter.simpleValueToString(value)
        assertEquals("${K_STRING_VALUE.toChar()}", result)
    }

    @Test
    fun `Boolean parsing - explicit true and false`() {
        val kb = K_BOOLEAN_VALUE.toChar()
        val trueValue = ValueStringConverter.stringToSimpleValue("${kb}true")
        assertEquals(ViaductSchema.TRUE, trueValue)

        val falseValue = ValueStringConverter.stringToSimpleValue("${kb}false")
        assertEquals(ViaductSchema.FALSE, falseValue)
    }

    @Test
    fun `CompoundDefaultValue comparison by depth`() {
        val list1 = ListConstant(1, listOf("a", "b"))
        val list2 = ListConstant(2, listOf("c", "d"))
        val obj1 = InputObjectConstant(1, mapOf("x" to "1"))
        val obj2 = InputObjectConstant(3, mapOf("y" to "2"))

        assert(list1 < list2)
        assert(list1 < obj2)
        assert(obj1 < obj2)
        assert(list2 < obj2)
    }

    @Test
    fun `CompoundDefaultValue key property for ListConstant`() {
        val elements = listOf("1", "2", "3")
        val listValue = ListConstant(1, elements)
        assertEquals(elements, listValue.key)
    }

    @Test
    fun `CompoundDefaultValue key property for InputObjectConstant`() {
        val fieldPairs = mapOf("x" to "1", "y" to "2")
        val objValue = InputObjectConstant(1, fieldPairs)
        assertEquals(fieldPairs, objValue.key)
    }
}
