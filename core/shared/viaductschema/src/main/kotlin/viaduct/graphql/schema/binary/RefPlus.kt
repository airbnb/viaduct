@file:Suppress("NOTHING_TO_INLINE")

package viaduct.graphql.schema.binary

//
// RefPlus-related value classes
//
// These provide type-safe wrappers around integer words that encode references
// plus metadata flags. Each context-specific variant makes the code self-documenting
// and prevents mixing up different contexts.

// Common bit field constants
private const val FLAG_0_BIT = (1 shl 30)
private const val FLAG_1_BIT = (1 shl 29)
private const val FLAG_2_BIT = (1 shl 28)

/**
 * RefPlus unused bits mask (bits 20-27 unused, bit 28 used for FLAG_2_BIT).
 * Per encoding.md: "Bits 20-27: unused (reserved for future use)"
 * Per invariants: "REFPLUS_UNUSED_BITS_ZERO: Bits 20-27 are zero (unless used for specific flags)"
 */
const val REFPLUS_UNUSED_MASK = 0x0FF00000

/**
 * A RefPlus for a type or directive definition.
 * Contains a source location index plus optional flags.
 */
@JvmInline
internal value class DefinitionRefPlus(val word: Int) {
    constructor(
        sourceLocationIndex: Int,
        hasImplementedTypes: Boolean = false,
        hasAppliedDirectives: Boolean = false,
        hasNext: Boolean = false
    ) : this(
        (if (hasNext) 0 else END_OF_LIST_BIT) or
            (if (hasImplementedTypes) FLAG_1_BIT else 0) or
            (if (hasAppliedDirectives) FLAG_0_BIT else 0) or
            sourceLocationIndex
    )

    /** Retrieve the source location index from this value. */
    inline fun getIndex() = word and IDX_MASK

    /** For interface and object types, does it implement any interfaces. */
    inline fun hasImplementedTypes() = word.isBitSet(FLAG_1_BIT)

    /** Does it have applied directives. */
    inline fun hasAppliedDirectives() = word.isBitSet(FLAG_0_BIT)

    /** For list elements, does this element have a next element (bit 31 is 0). */
    inline fun hasNext() = 0 <= word
}

/**
 * A consolidated RefPlus for fields (both input-like and output fields).
 * Used for:
 * - Directive definition arguments (input-like: has default value, no arguments)
 * - Output field arguments (input-like: has default value, no arguments)
 * - Input object type fields (input-like: has default value, no arguments)
 * - Interface fields (output: has arguments, no default value)
 * - Object fields (output: has arguments, no default value)
 *
 * Contains a name identifier index, default value flag, arguments flag,
 * applied directives flag, and list continuation flag.
 * When hasNext is true, END_OF_LIST_BIT is 0 (more elements follow).
 * When hasNext is false, END_OF_LIST_BIT is 1 (last element).
 */
@JvmInline
internal value class FieldRefPlus(val word: Int) {
    constructor(
        nameIndex: Int,
        hasDefaultValue: Boolean = false,
        hasArguments: Boolean = false,
        hasAppliedDirectives: Boolean = false,
        hasNext: Boolean = false
    ) : this(
        (if (hasNext) 0 else END_OF_LIST_BIT) or
            (if (hasAppliedDirectives) FLAG_0_BIT else 0) or
            (if (hasDefaultValue) FLAG_1_BIT else 0) or
            (if (hasArguments) FLAG_2_BIT else 0) or
            nameIndex
    )

    /** Retrieve the name identifier index from this value. */
    inline fun getIndex() = word and IDX_MASK

    /** Does this field have a default value (input-like fields only). */
    inline fun hasDefaultValue() = word.isBitSet(FLAG_1_BIT)

    /** Does this field have arguments (output fields only). */
    inline fun hasArguments() = word.isBitSet(FLAG_2_BIT)

    /** Does it have applied directives. */
    inline fun hasAppliedDirectives() = word.isBitSet(FLAG_0_BIT)

    /** For list elements, does this element have a next element (bit 31 is 0). */
    inline fun hasNext() = 0 <= word
}

// Backwards compatibility type aliases
internal typealias InputLikeFieldRefPlus = FieldRefPlus
internal typealias OutputFieldRefPlus = FieldRefPlus

/**
 * A RefPlus for an applied directive.
 * Contains a directive name identifier index, arguments flag, and list continuation flag.
 */
@JvmInline
internal value class AppliedDirectiveRefPlus(val word: Int) {
    constructor(
        nameIndex: Int,
        hasArguments: Boolean,
        hasNext: Boolean
    ) : this(
        (if (hasNext) 0 else END_OF_LIST_BIT) or
            (if (hasArguments) FLAG_1_BIT else 0) or
            nameIndex
    )

    /** Retrieve the directive name identifier index from this value. */
    inline fun getIndex() = word and IDX_MASK

    /** Does this directive application have arguments. */
    inline fun hasArguments() = word.isBitSet(FLAG_1_BIT)

    /** For list elements, does this element have a next element (bit 31 is 0). */
    inline fun hasNext() = 0 <= word
}

/**
 * A RefPlus for an applied directive argument.
 * Contains an argument name identifier index and list continuation flag.
 * Note: Applied directive arguments are just name-value pairs with no type information.
 */
@JvmInline
internal value class AppliedDirectiveArgRefPlus(val word: Int) {
    constructor(
        nameIndex: Int,
        hasNext: Boolean
    ) : this(
        (if (hasNext) 0 else END_OF_LIST_BIT) or nameIndex
    )

    /** Retrieve the argument name identifier index from this value. */
    inline fun getIndex() = word and IDX_MASK

    /** For list elements, does this element have a next element (bit 31 is 0). */
    inline fun hasNext() = 0 <= word
}

/**
 * A RefPlus for an enumeration value.
 * Contains a name identifier index, has-applied directive field, and list continuation flag.
 * When hasNext is true, END_OF_LIST_BIT is 0 (more elements follow).
 * When hasNext is false, END_OF_LIST_BIT is 1 (last element).
 */
@JvmInline
internal value class EnumValueRefPlus(val word: Int) {
    constructor(
        nameIndex: Int,
        hasAppliedDirectives: Boolean,
        hasNext: Boolean
    ) : this(
        (if (hasNext) 0 else END_OF_LIST_BIT) or
            (if (hasAppliedDirectives) FLAG_0_BIT else 0) or
            nameIndex
    )

    /** Retrieve the name identifier index from this value. */
    inline fun getIndex() = word and IDX_MASK

    /** Does it have applied directives. */
    inline fun hasAppliedDirectives() = word.isBitSet(FLAG_0_BIT)

    /** For list elements, does this element have a next element (bit 31 is 0). */
    inline fun hasNext() = 0 <= word
}

/**
 * A RefPlus for a definition stub in the Definition Stubs section.
 * Contains an identifier index (bits 0-19) and kind code (bits 24-31).
 * Bits 20-23 are unused and must be zero.
 */
@JvmInline
internal value class StubRefPlus(val word: Int) {
    constructor(
        identifierIndex: Int,
        kindCode: Int
    ) : this(identifierIndex or (kindCode shl 24))

    /** Retrieve the identifier index from this value. */
    inline fun getIdentifierIndex() = word and IDX_MASK

    /** Retrieve the kind code from this value. */
    inline fun getKindCode() = (word ushr 24) and 0xFF
}
