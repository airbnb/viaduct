package viaduct.graphql.schema.binary

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectValue
import graphql.language.StringValue
import graphql.language.Value
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Converts GraphQL values between Value instances and intermediate representations
 * for binary encoding.
 *
 * ViaductSchema represents default values and values for applied-directive arguments
 * as follows:
 *
 * - GraphQL InputType -> Kotlin Type
 * - _NullValue_ -> `null`
 * - _IntValue_, `FloatValue`, `StringValue`, `BooleanValue`, `EnumValue` -> graphql-java's corresponding `graphql.language.*Value` types
 * - _ListValue_ -> `ArrayValue` (recursively following this convention)
 * - _ObjectValue_ -> `ObjectValue` (recursively following this convention)
 *
 * Our binary schema format has a special section for handling so-called "simple"
 * types, ie, all InputTypes other than _ListValue_ and _ObjectValue_. In this
 * section, values are represented by a string representation of the value itself,
 * prefixed with a "kind" code which tells us if it's an `IntValue` or `StringValue`
 * and so forth. Specifically:
 *
 * - Null: K_NULL_VALUE + ""
 * - Boolean: K_BOOLEAN_VALUE + "true" or "false"
 * - Int: K_INT_VALUE + decimal string representation
 * - Float: K_FLOAT_VALUE + decimal string representation
 * - String: K_STRING_VALUE + raw string value
 * - Enum: K_ENUM_VALUE + enum value name
 */
internal object ValueStringConverter {
    /**
     * Converts a Value instance to its string representation with kind code prefix.
     *
     * @param value The value to convert (must be a scalar or enum value, not null)
     * @return The kind-code-prefixed string representation
     * @throws IllegalArgumentException if value is not a simple type
     */
    fun simpleValueToString(value: Value<*>): String {
        return when (value) {
            is BooleanValue -> "${K_BOOLEAN_VALUE.toChar()}${value.isValue}"
            is IntValue -> "${K_INT_VALUE.toChar()}${value.value}"
            is FloatValue -> "${K_FLOAT_VALUE.toChar()}${value.value}"
            is StringValue -> "${K_STRING_VALUE.toChar()}${value.value ?: ""}"
            is EnumValue -> "${K_ENUM_VALUE.toChar()}${value.name}"
            else -> throw IllegalArgumentException("Unsupported Value type for simple conversion: ${value.javaClass}")
        }
    }

    /**
     * Converts a kind-code-prefixed string representation back to a Value instance.
     *
     * @param encodedStr The kind-code-prefixed string representation
     * @return Value instance matching the kind code
     * @throws IllegalArgumentException if kind code is invalid or string cannot be parsed
     */
    fun stringToSimpleValue(encodedStr: String): Value<*> {
        require(encodedStr.isNotEmpty()) { "Encoded string cannot be empty" }

        val kindCode = encodedStr[0].code
        val content = encodedStr.substring(1)

        return when (kindCode) {
            K_BOOLEAN_VALUE -> {
                when (content) {
                    "true" -> BooleanValue(true)
                    "false" -> BooleanValue(false)
                    else -> throw IllegalArgumentException("Invalid boolean value: $content (expected 'true' or 'false')")
                }
            }
            K_INT_VALUE -> {
                try {
                    IntValue(BigInteger(content))
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Invalid integer value: $content", e)
                }
            }
            K_FLOAT_VALUE -> {
                try {
                    FloatValue(BigDecimal(content))
                } catch (e: NumberFormatException) {
                    throw IllegalArgumentException("Invalid float value: $content", e)
                }
            }
            K_STRING_VALUE -> {
                StringValue(content)
            }
            K_ENUM_VALUE -> {
                EnumValue(content)
            }
            else -> throw IllegalArgumentException("Unknown kind code: 0x${kindCode.toString(16)}")
        }
    }

    /**
     * Converts a Value instance (including compound values) to its representation
     * as Any? following the default value encoding rules.
     *
     * @param value The value to convert
     * @return The converted representation: null, String, ListConstant, or InputObjectConstant
     */
    fun valueToString(value: Value<*>): Any? {
        return when (value) {
            is NullValue -> null
            is ArrayValue -> {
                // Convert each element recursively
                val elements = value.values.map { elementValue ->
                    valueToString(elementValue)
                }

                // Calculate depth: 1 + max depth of elements
                val maxElementDepth = elements.maxOfOrNull { depthOf(it) } ?: 0
                ListConstant(1 + maxElementDepth, elements)
            }
            is ObjectValue -> {
                // Convert field values recursively
                val fieldPairs = value.objectFields.associate { field ->
                    field.name to valueToString(field.value)
                }

                // Calculate depth: 1 + max depth of field values
                val maxFieldDepth = fieldPairs.values.maxOfOrNull { depthOf(it) } ?: 0
                InputObjectConstant(1 + maxFieldDepth, fieldPairs)
            }
            // Simple scalar/enum values
            else -> simpleValueToString(value)
        }
    }

    /**
     * Calculates the depth of a constant representation.
     *
     * @param obj The value representation
     * @return Depth: 0 for null/String, obj.depth for CompoundConstant
     * @throws IllegalArgumentException for other types
     */
    private fun depthOf(obj: Any?): Int {
        return when (obj) {
            null -> 0
            is String -> 0
            is CompoundConstant -> obj.depth
            else -> throw IllegalArgumentException("Unexpected value type: ${obj?.javaClass}")
        }
    }
}
