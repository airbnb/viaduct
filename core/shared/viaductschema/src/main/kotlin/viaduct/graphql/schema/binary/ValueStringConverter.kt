package viaduct.graphql.schema.binary

import viaduct.graphql.schema.ViaductSchema

/**
 * Converts GraphQL values between ViaductSchema.Literal instances and intermediate representations
 * for binary encoding.
 *
 * ViaductSchema represents default values and values for applied-directive arguments
 * using ViaductSchema.Literal types:
 *
 * - GraphQL InputType -> Kotlin Type
 * - _NullValue_ -> `ViaductSchema.NullLiteral`
 * - _IntValue_, `FloatValue`, `StringValue`, `BooleanValue`, `EnumValue` -> ViaductSchema.*Value types
 * - _ListValue_ -> `ViaductSchema.ListLiteral` (recursively following this convention)
 * - _ObjectValue_ -> `ViaductSchema.ObjectLiteral` (recursively following this convention)
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
     * Converts a ViaductSchema.Literal instance to its string representation with kind code prefix.
     *
     * @param value The value to convert (must be a scalar or enum value, not null)
     * @return The kind-code-prefixed string representation
     * @throws IllegalArgumentException if value is not a simple type
     */
    fun simpleValueToString(value: ViaductSchema.Literal): String {
        return when (value) {
            is ViaductSchema.TrueLiteral -> "${K_BOOLEAN_VALUE.toChar()}true"
            is ViaductSchema.FalseLiteral -> "${K_BOOLEAN_VALUE.toChar()}false"
            is ViaductSchema.IntLiteral -> "${K_INT_VALUE.toChar()}$value"
            is ViaductSchema.FloatLiteral -> "${K_FLOAT_VALUE.toChar()}$value"
            is ViaductSchema.StringLiteral -> "${K_STRING_VALUE.toChar()}${value.value}"
            is ViaductSchema.EnumLit -> "${K_ENUM_VALUE.toChar()}${value.value}"
            else -> throw IllegalArgumentException("Unsupported Value type for simple conversion: ${value.javaClass}")
        }
    }

    /**
     * Converts a kind-code-prefixed string representation back to a ViaductSchema.Literal instance.
     *
     * @param encodedStr The kind-code-prefixed string representation
     * @return ViaductSchema.Literal instance matching the kind code
     * @throws IllegalArgumentException if kind code is invalid or string cannot be parsed
     */
    fun stringToSimpleValue(encodedStr: String): ViaductSchema.Literal {
        require(encodedStr.isNotEmpty()) { "Encoded string cannot be empty" }

        val kindCode = encodedStr[0].code
        val content = encodedStr.substring(1)

        return when (kindCode) {
            K_BOOLEAN_VALUE -> {
                when (content) {
                    "true" -> ViaductSchema.TRUE
                    "false" -> ViaductSchema.FALSE
                    else -> throw IllegalArgumentException("Invalid boolean value: $content (expected 'true' or 'false')")
                }
            }
            K_INT_VALUE -> {
                try {
                    ViaductSchema.IntLiteral.of(content)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid integer value: $content", e)
                }
            }
            K_FLOAT_VALUE -> {
                try {
                    ViaductSchema.FloatLiteral.of(content)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("Invalid float value: $content", e)
                }
            }
            K_STRING_VALUE -> {
                ViaductSchema.StringLiteral.of(content)
            }
            K_ENUM_VALUE -> {
                ViaductSchema.EnumLit.of(content)
            }
            else -> throw IllegalArgumentException("Unknown kind code: 0x${kindCode.toString(16)}")
        }
    }

    /**
     * Converts a ViaductSchema.Literal instance (including compound values) to its representation
     * as Any? following the default value encoding rules.
     *
     * @param value The value to convert
     * @return The converted representation: null, String, ListConstant, or InputObjectConstant
     */
    fun valueToString(value: ViaductSchema.Literal): Any? {
        return when (value) {
            is ViaductSchema.NullLiteral -> null
            is ViaductSchema.ListLiteral -> {
                // Convert each element recursively
                val elements = value.map { elementValue ->
                    valueToString(elementValue)
                }

                // Calculate depth: 1 + max depth of elements
                val maxElementDepth = elements.maxOfOrNull { depthOf(it) } ?: 0
                ListConstant(1 + maxElementDepth, elements)
            }
            is ViaductSchema.ObjectLiteral -> {
                // Convert field values recursively
                val fieldPairs = value.entries.associate { (fieldName, fieldValue) ->
                    fieldName to valueToString(fieldValue)
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
            else -> throw IllegalArgumentException("Unexpected value type: ${obj.javaClass}")
        }
    }
}
