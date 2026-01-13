package viaduct.graphql.schema.binary

import graphql.language.ArrayValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.Value

/**
 * Decodes both simple and compound constants sections.
 *
 * This class provides methods to decode constant values based on their
 * type expressions and indices.
 *
 * @param identifiers The identifiers decoder (needed for field names in InputObject constants)
 * @param simpleConstants Array of kind-code-prefixed strings for simple constants
 * @param compoundConstants Raw bytes of the compound constants section (after magic number)
 * @param compoundConstantStarts Index of starting offset for each compound constant
 */
internal class ConstantsDecoder(
    private val identifiers: IdentifiersDecoder,
    private val simpleConstants: Array<String>,
    private val compoundConstants: ByteArray,
    private val compoundConstantStarts: IntArray
) {
    companion object {
        /**
         * Reads constants sections from the binary stream.
         *
         * @param data The input stream positioned at the start of the simple constants section
         * @param header The decoded header containing counts and sizes
         * @param identifiers The identifiers decoder
         * @return A new ConstantsDecoder instance
         */
        fun fromFile(
            data: BInputStream,
            header: HeaderSection,
            identifiers: IdentifiersDecoder
        ): ConstantsDecoder {
            data.validateMagicNumber(MAGIC_SIMPLE_CONSTANTS, "simple constants")

            // Each entry is a kind-code-prefixed UTF-8 string (kind code + content + 0x00)
            // Entry 0 is K_NULL_VALUE + 0x00 (kind code with empty content)
            val simpleConstants = Array(header.simpleConstantCount) {
                data.readUTF8String()
            }

            data.skipPadding()

            data.validateMagicNumber(MAGIC_COMPOUND_CONSTANTS, "compound constants")

            // Read entire compound constants section into memory (excluding the magic number we just read)
            val compoundConstantDataBytes = header.compoundConstantBytes - WORD_SIZE
            val compoundConstants = ByteArray(compoundConstantDataBytes)
            for (i in 0 until compoundConstantDataBytes) {
                compoundConstants[i] = data.read().toByte()
            }

            // Build index of compound constant starts
            val compoundConstantStarts = IntArray(header.compoundConstantCount)
            var offset = 0
            for (i in 0 until header.compoundConstantCount) {
                compoundConstantStarts[i] = offset
                if (i == 0) {
                    // First entry is EMPTY_LIST_MARKER (4 bytes)
                    offset += 4
                } else if (i == 1) {
                    // Second entry is EMPTY_OBJECT_MARKER (4 bytes)
                    offset += 4
                } else {
                    // Parse to find the size of this compound constant
                    offset += sizeOfCompoundConstant(compoundConstants, offset)
                }
            }

            return ConstantsDecoder(identifiers, simpleConstants, compoundConstants, compoundConstantStarts)
        }

        /**
         * Calculate the size in bytes of a compound constant starting at the given offset.
         */
        private fun sizeOfCompoundConstant(
            compoundConstants: ByteArray,
            startOffset: Int
        ): Int {
            val firstWord = readIntAt(compoundConstants, startOffset)
            if (firstWord == EMPTY_LIST_MARKER) return 4
            if (firstWord == EMPTY_OBJECT_MARKER) return 4

            var size = 4
            var word = firstWord
            val isList = (firstWord and IS_LIST_CONSTANT) != 0

            if (isList) {
                // List: count words until END_OF_LIST_BIT
                while ((word and END_OF_LIST_BIT) == 0) {
                    word = readIntAt(compoundConstants, startOffset + size)
                    size += 4
                }
            } else {
                // Input object: count pairs of words until END_OF_LIST_BIT on field name
                while ((word and END_OF_LIST_BIT) == 0) {
                    size += 4 // Skip value word
                    word = readIntAt(compoundConstants, startOffset + size)
                    size += 4 // Skip next field name word
                }
                size += 4 // Count the final value word
            }

            return size
        }

        private fun readIntAt(
            compoundConstants: ByteArray,
            offset: Int
        ): Int {
            val b0 = compoundConstants[offset].toInt() and 0xFF
            val b1 = compoundConstants[offset + 1].toInt() and 0xFF
            val b2 = compoundConstants[offset + 2].toInt() and 0xFF
            val b3 = compoundConstants[offset + 3].toInt() and 0xFF
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }
    }

    /**
     * Decode a constant value given its constant reference index.
     */
    fun decodeConstant(constantRef: Int): Value<*> {
        return if (constantRef < simpleConstants.size) {
            // Simple constant
            decodeSimpleConstant(constantRef)
        } else {
            // Compound constant
            val compoundIdx = constantRef - simpleConstants.size
            decodeCompoundConstant(compoundIdx)
        }
    }

    private fun decodeSimpleConstant(idx: Int): Value<*> {
        val encodedStr = simpleConstants[idx]
        // Check if this is null
        if (encodedStr.isNotEmpty() && encodedStr[0].code == K_NULL_VALUE) {
            return NullValue.newNullValue().build()
        }
        // Otherwise convert using ValueStringConverter (it determines type from kind code)
        return ValueStringConverter.stringToSimpleValue(encodedStr)
    }

    // Internal for testing
    internal fun decodeCompoundConstant(compoundIdx: Int): Value<*> {
        val offset = compoundConstantStarts[compoundIdx]

        // Check for empty markers
        val firstWord = readIntAt(offset)
        if (firstWord == EMPTY_LIST_MARKER) {
            return ArrayValue.newArrayValue().build()
        }
        if (firstWord == EMPTY_OBJECT_MARKER) {
            return ObjectValue.newObjectValue().build()
        }

        // Check if this is a list (bit 30 set on first word)
        return if ((firstWord and IS_LIST_CONSTANT) != 0) {
            decodeListConstant(offset)
        } else {
            decodeInputObjectConstant(offset)
        }
    }

    private fun decodeListConstant(startOffset: Int): ArrayValue {
        val elements = mutableListOf<Value<*>>()
        var offset = startOffset
        var word = readIntAt(offset)

        while (true) {
            val elementRef = word and IDX_MASK
            elements.add(decodeConstant(elementRef))
            if ((word and END_OF_LIST_BIT) != 0) break
            offset += 4
            word = readIntAt(offset)
        }

        return ArrayValue.newArrayValue().values(elements).build()
    }

    private fun decodeInputObjectConstant(startOffset: Int): ObjectValue {
        val fields = mutableListOf<ObjectField>()
        var offset = startOffset

        while (true) {
            val fieldNameRef = readIntAt(offset)
            val fieldName = identifiers.get(fieldNameRef)
            val valueRef = readIntAt(offset + 4)

            val value = decodeConstant(valueRef)
            fields.add(ObjectField.newObjectField().name(fieldName).value(value).build())

            if ((fieldNameRef and END_OF_LIST_BIT) != 0) break
            offset += 8
        }

        return ObjectValue.newObjectValue().objectFields(fields).build()
    }

    private fun readIntAt(offset: Int): Int {
        val b0 = compoundConstants[offset].toInt() and 0xFF
        val b1 = compoundConstants[offset + 1].toInt() and 0xFF
        val b2 = compoundConstants[offset + 2].toInt() and 0xFF
        val b3 = compoundConstants[offset + 3].toInt() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }
}
