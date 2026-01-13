package viaduct.graphql.schema.binary

import com.google.common.base.Utf8

/**
 * Immutable encoder for constant values.
 * Provides reference lookup and sorted access to constants for encoding.
 *
 * Simple constants are stored as kind-code-prefixed strings where the first
 * character is the kind code (K_NULL_VALUE, K_INT_VALUE, etc.) followed by
 * the string content.
 *
 * Use the nested Builder class to construct instances.
 */
internal class ConstantsEncoder private constructor(
    simpleValues: Set<String>,
    compoundValues: Set<CompoundConstant>,
    maxSimpleValueLen: Int,
    val simpleConstantsBytes: Int,
    val compoundConstantsBytes: Int
) {
    /** Length of largest string in bytes */
    val maxSimpleValueLen: Int = maxSimpleValueLen
    val sortedSimpleValues: List<String> = simpleValues.sorted()
    val sortedCompoundValues: List<CompoundConstant> = compoundValues.sorted()

    /** Count of simple constants including null placeholder */
    val simpleConstantsCount: Int = sortedSimpleValues.size + 1 // +1 for null

    /** Count of compound constants including EMPTY_LIST_MARKER and EMPTY_OBJECT_MARKER */
    val compoundConstantsCount: Int = sortedCompoundValues.size + 2 // +2 for EMPTY_LIST_MARKER and EMPTY_OBJECT_MARKER

    private val simpleValueMap: Map<String, Int> = sortedSimpleValues
        .withIndex()
        .associate { (idx, value) -> value to (idx + 1) } // +1 for null placeholder

    private val compoundValueMap: Map<CompoundConstant, Int> = sortedCompoundValues
        .withIndex()
        .associate { (idx, value) ->
            value to (idx + sortedSimpleValues.size + 3) // +3 for null, EMPTY_LIST_MARKER, and EMPTY_OBJECT_MARKER
        }

    /**
     * Finds the reference index for a constant value.
     *
     * @param value The value to look up (null, String, ListConstant, or InputObjectConstant)
     * @return The index in the combined constants space
     */
    fun findRef(value: Any?): Int {
        return when (value) {
            null -> 0
            is String -> simpleValueMap[value]
                ?: throw NoSuchElementException("Simple value not found: $value")
            is ListConstant -> {
                if (value.elements.isEmpty()) {
                    sortedSimpleValues.size + 1 // EMPTY_LIST_MARKER
                } else {
                    compoundValueMap[value]
                        ?: throw NoSuchElementException("Compound value not found: $value")
                }
            }
            is InputObjectConstant -> {
                if (value.fieldPairs.isEmpty()) {
                    sortedSimpleValues.size + 2 // EMPTY_OBJECT_MARKER
                } else {
                    compoundValueMap[value]
                        ?: throw NoSuchElementException("Compound value not found: $value")
                }
            }
            else -> throw IllegalArgumentException("Unexpected value type: ${value.javaClass}")
        }
    }

    /**
     * Encodes the simple values section to the output stream (NO padding).
     * Each entry is: kind code byte + content + null terminator.
     * Entry 0 is the null value: K_NULL_VALUE + 0x00.
     */
    fun encodeSimpleValues(out: BOutputStream) {
        out.writeInt(MAGIC_SIMPLE_CONSTANTS)

        // Null placeholder: K_NULL_VALUE followed by 0
        out.write(K_NULL_VALUE)
        out.write(0)

        // Write all other simple values (already kind-code-prefixed)
        for (value in sortedSimpleValues) {
            out.writeUTF8String(value)
        }
        out.pad()
    }

    /**
     * Encodes the compound values section to the output stream (NO padding).
     * Uses the CompoundConstant natural ordering (by depth) which guarantees no forward references.
     *
     * @param out The output stream to write to
     * @param identifierIndex Function to look up identifier index by name
     */
    fun encodeCompoundValues(
        out: BOutputStream,
        identifierIndex: (String) -> Int
    ) {
        out.writeInt(MAGIC_COMPOUND_CONSTANTS)
        out.writeInt(EMPTY_LIST_MARKER) // Write EMPTY_LIST_MARKER as first element
        out.writeInt(EMPTY_OBJECT_MARKER) // Write EMPTY_OBJECT_MARKER as second element

        // Encode each compound value (using same order as in buildMaps)
        for (value in sortedCompoundValues) {
            when (value) {
                is ListConstant -> {
                    val elements = value.elements
                    for (i in elements.indices) {
                        var ref = findRef(elements[i])

                        // Set IS_LIST_CONSTANT on first element
                        if (i == 0) {
                            ref = ref or IS_LIST_CONSTANT
                        }

                        // Set END_OF_LIST_BIT on last element
                        if (i == elements.lastIndex) {
                            ref = ref or END_OF_LIST_BIT
                        }

                        out.writeInt(ref)
                    }
                }
                is InputObjectConstant -> {
                    // Encode input object: pairs of (identifier, value) references
                    val entries = value.fieldPairs.entries.toList()
                    for (i in entries.indices) {
                        val (fieldName, fieldValue) = entries[i]

                        var identifierRef = identifierIndex(fieldName)

                        // Set END_OF_LIST_BIT on last identifier
                        if (i == entries.lastIndex) {
                            identifierRef = identifierRef or END_OF_LIST_BIT
                        }

                        out.writeInt(identifierRef)
                        out.writeInt(findRef(fieldValue))
                    }
                }
            }
        }
    }

    /**
     * Builder for collecting constant values during schema traversal.
     * This is the mutable "building" phase.
     *
     * Simple values added via addValue() should already be kind-code-prefixed strings
     * (from ValueStringConverter.simpleValueToString()).
     */
    internal class Builder {
        private val simpleValues: MutableSet<String> = mutableSetOf()
        private val compoundValues: MutableSet<CompoundConstant> = mutableSetOf()

        private var maxSimpleValueLen = 0

        /**
         * Number of bytes needed for the simple-value strings section
         * (including magic number, terminators, and the `null` encoding).
         * Initialize to WORD_SIZE + 2 to account for:
         * - Section magic number (4 bytes)
         * - Null encoding: K_NULL_VALUE + 0x00 (2 bytes)
         */
        private var simpleConstantsBytes = WORD_SIZE + 2

        /**
         * Number of bytes needed for the compound-value section
         * (including magic number and the [EMPTY_LIST_MARKER] and [EMPTY_OBJECT_MARKER] encodings).
         * Initialize to 3 * WORD_SIZE to account for:
         * - Section magic number (4 bytes)
         * - EMPTY_LIST_MARKER encoding (4 bytes)
         * - EMPTY_OBJECT_MARKER encoding (4 bytes)
         */
        private var compoundConstantsBytes = 3 * WORD_SIZE

        /**
         * Adds a value to the builder (null, String, ListConstant, or InputObjectConstant).
         *
         * @param value The representation to add
         */
        fun addValue(value: Any?) {
            when (value) {
                null -> { /* skip - null is always at index 0 */ }
                is String -> {
                    if (simpleValues.add(value)) {
                        val byteLen = Utf8.encodedLength(value)
                        if (maxSimpleValueLen < byteLen) maxSimpleValueLen = byteLen
                        simpleConstantsBytes += (byteLen + 1)
                    }
                }
                is ListConstant -> {
                    value.elements.forEach { addValue(it) }
                    // Only add non-empty lists to compound values (empty uses EMPTY_LIST_MARKER)
                    if (value.elements.isNotEmpty() && compoundValues.add(value)) {
                        compoundConstantsBytes += WORD_SIZE * value.elements.size
                    }
                }
                is InputObjectConstant -> {
                    value.fieldPairs.values.forEach { addValue(it) }
                    // Only add non-empty objects to compound values (empty uses EMPTY_LIST_MARKER)
                    if (value.fieldPairs.isNotEmpty() && compoundValues.add(value)) {
                        compoundConstantsBytes += 2 * WORD_SIZE * value.fieldPairs.size
                    }
                }
                else -> throw IllegalArgumentException("Unsupported type $value (${value::class})")
            }
        }

        /**
         * Builds the immutable ConstantsEncoder.
         * After calling this, no more values can be added.
         */
        fun build(): ConstantsEncoder {
            return ConstantsEncoder(
                simpleValues,
                compoundValues,
                maxSimpleValueLen,
                simpleConstantsBytes,
                compoundConstantsBytes
            )
        }
    }
}
