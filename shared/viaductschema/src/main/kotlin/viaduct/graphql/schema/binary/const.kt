package viaduct.graphql.schema.binary

import viaduct.graphql.schema.ViaductSchema
import viaduct.utils.collections.BitVector

//
// Misc.

internal const val MAX_STRING_LEN = 65536

/** Mask for word alignment. */
internal const val WORD_ALIGN_MASK = WORD_SIZE - 1

/** Number of 32-bit words that make up the header section. */
internal const val HEADER_SIZE_IN_WORDS = 16

/** Magic number identifying binary schema files. */
internal const val MAGIC_NUMBER: Int = 0xA75F2B1C.toInt()

/** File format version using semantic versioning (major.minor in bytes 1.0). */
internal const val FILE_VERSION: Int = 0x00000002

/**
 * File version unused bits mask (bits 16-31 unused).
 * Per encoding.md: "Most significant byte (bits 16-31): unused"
 */
internal const val FILE_VERSION_UNUSED_MASK = 0xFFFF0000.toInt()

//
// Section magic numbers for validation

/** Magic number for Identifiers section ("IDNT" in ASCII). */
internal const val MAGIC_IDENTIFIERS: Int = 0x49444E54

/** Magic number for Source Locations section ("SLOC" in ASCII). */
internal const val MAGIC_SOURCE_LOCATIONS: Int = 0x534C4F43

/** Magic number for Simple Constants section ("SCON" in ASCII). */
internal const val MAGIC_SIMPLE_CONSTANTS: Int = 0x53434F4E

/** Magic number for Compound Constants section ("CCON" in ASCII). */
internal const val MAGIC_COMPOUND_CONSTANTS: Int = 0x43434F4E

/** Magic number for Type Expressions section ("TEXP" in ASCII). */
internal const val MAGIC_TYPE_EXPRS: Int = 0x54455850

/** Magic number for Root Types section ("ROOT" in ASCII). */
internal const val MAGIC_ROOT_TYPES: Int = 0x524F4F54

/** Magic number for Definitions section ("DEFS" in ASCII). */
internal const val MAGIC_DEFINITIONS: Int = 0x44454653

/** Magic number for Definition Stubs section ("STUB" in ASCII). */
internal const val MAGIC_DEFINITION_STUBS: Int = 0x53545542

//
// Definition stub kind codes (used in Definition Stubs section)

internal const val K_DIRECTIVE: Int = 0b1000 shl 4
internal const val K_ENUM: Int = 0b1001 shl 4
internal const val K_INPUT: Int = 0b1010 shl 4
internal const val K_INTERFACE: Int = 0b1011 shl 4
internal const val K_OBJECT: Int = 0b1100 shl 4
internal const val K_SCALAR: Int = 0b1101 shl 4
internal const val K_UNION: Int = 0b1110 shl 4

/**
 * Mask for extracting the kind marker from terminator byte (high nibble).
 */
internal const val IDENTIFIER_TERMINATOR_KIND_MASK = 0xF0

/**
 * Identifier terminator low nibble mask (bits 0-3 unused).
 * Per encoding.md: "The low-order four bits of the terminator byte are unused."
 * Per invariants: "KIND_MARKER_LOW_BITS_ZERO: Low nibble of kind marker is zero (bits 0-3 unused)"
 */
internal const val IDENTIFIER_TERMINATOR_LOW_NIBBLE_MASK = 0x0F

//
// Reference related definitions

/**
 * Mask for the reference-carrying bits of an encoding word.
 * For now we limit outselves to 2^20 identifiers, strings, etc.
 */
internal const val IDX_MASK = 0x000FFFFF

//
// List-related definitions

/**
 * The encodings of various lists use the most significant bit of a
 * list element to determine whether the element is the last element
 * of the list.
 */
internal const val END_OF_LIST_BIT = 1 shl 31

/** Marker value indicating an empty list (fields or enum values). */
internal const val EMPTY_LIST_MARKER = -1

/** Marker value indicating an empty input object. */
internal const val EMPTY_OBJECT_MARKER = -2

//
// Type-expression related definitions

@JvmInline
internal value class TexprWordOne(val word: Int) {
    constructor(baseTypeIdx: Int, te: ViaductSchema.TypeExpr) : this(encode(baseTypeIdx, te))

    inline fun baseTypeNullable() = (0 != (word and BASE_TYPE_NULLABLE_BIT))

    inline fun code() = (word ushr 28) and TWO_WORD_CODE

    inline fun needsWordTwo() = (code() == TWO_WORD_CODE)

    inline fun listNullableVec() = CODE_TO_VEC[code()]!!

    inline fun typeIndex() = word and IDX_MASK

    companion object {
        private fun encode(
            baseTypeIdx: Int,
            te: ViaductSchema.TypeExpr
        ): Int {
            val code = (VEC_TO_CODE[te.listNullable] ?: TWO_WORD_CODE) shl 28
            val baseNullableBit = (if (te.baseTypeNullable) 1 else 0) shl 31
            return code or baseNullableBit or baseTypeIdx
        }

        const val BASE_TYPE_NULLABLE_BIT = 1 shl 31
        const val TWO_WORD_CODE = 0b111

        /**
         * TypeExpr first word unused bits mask (bits 20-27 unused).
         * Per encoding.md: "Bits 20-27: unused"
         * Per invariants: "TEXPR_UNUSED_BITS_ZERO: Bits 20-27 are zero"
         */
        const val UNUSED_MASK = 0x0FF00000

        /**
         * Map from [ViaductSchema.TypeExpr.listNullable] to code for
         * single-word encoding.  No entry means two-word encoding is
         * needed.
         */
        val VEC_TO_CODE: Map<BitVector, Int> = mapOf(
            bv(0b000, 0) to 0b000, // no list
            bv(0b001, 1) to 0b001, // [*]
            bv(0b000, 1) to 0b010, // [*]!
            bv(0b011, 2) to 0b011, // [[*]]
            bv(0b001, 2) to 0b100, // [[*]!]
            bv(0b010, 2) to 0b101, // [[*]]!
            bv(0b000, 2) to 0b110, // [[*]!]!
        )

        /**
         * Map from code used in single-word type-expr encodings to the
         * corresponding [ViaductSchema.TypeExpr.listNullable] bit vector.
         * This is the transpose of [VEC_TO_CODE].
         */
        val CODE_TO_VEC: Map<Int, BitVector> =
            VEC_TO_CODE.entries.associate { (vec, code) -> code to vec }

        inline fun bv(
            bits: Int,
            size: Int
        ): BitVector = BitVector.Builder().add(bits.toLong(), size).build()

        fun typeExprByteSize(te: ViaductSchema.TypeExpr) = if (VEC_TO_CODE[te.listNullable] != null) WORD_SIZE else 2 * WORD_SIZE
    }
}

@JvmInline
internal value class TexprWordTwo(val word: Int) {
    constructor(te: ViaductSchema.TypeExpr) : this(encode(te))

    inline fun listDepth(): Int = (word ushr MAX_LIST_DEPTH) and 0x1F

    fun listNullableVec(): BitVector {
        val depth = listDepth()
        return TexprWordOne.bv(word and ((1 shl depth) - 1), depth)
    }

    companion object {
        const val MAX_LIST_DEPTH = 27

        private fun encode(te: ViaductSchema.TypeExpr): Int {
            val depth = te.listDepth.takeIf { it <= MAX_LIST_DEPTH }
                ?: throw IllegalArgumentException("Max list depth exceeded ($te).")
            return (depth shl MAX_LIST_DEPTH) or te.listNullable.get(0, depth).toInt()
        }
    }
}

//
// Root-type related definitions

/** Marker value indicating an undefined root type (mutation or subscription). */
internal const val UNDEFINED_ROOT_MARKER = -1

//
// Directive-definition related definitions

@JvmInline
internal value class DirectiveInfo(val word: Int) {
    constructor(directive: ViaductSchema.Directive) : this(encode(directive))

    inline fun isRepeatable() = word.isBitSet(REPEATABLE_BIT)

    inline fun hasArgs() = word.isBitSet(HAS_ARGS_BIT)

    fun allowedLocations(): Set<ViaductSchema.Directive.Location> {
        val mutableLocations = mutableSetOf<ViaductSchema.Directive.Location>()
        for (loc in ViaductSchema.Directive.Location.values()) {
            if ((word and (1 shl (LOCATION_START_BIT + loc.ordinal))) != 0) {
                mutableLocations.add(loc)
            }
        }
        return mutableLocations
    }

    companion object {
        const val REPEATABLE_BIT = 0x00000001 // Bit 0
        const val LOCATION_START_BIT = 1 // First location bit (bits 1-19)
        const val HAS_ARGS_BIT = 1 shl 31 // Bit 31

        /**
         * DirectiveInfo unused bits mask (bits 20-30 unused).
         * Per encoding.md: "Bits 20-30: unused"
         * Per invariants: "DIRECTIVE_INFO_UNUSED_BITS_ZERO: Bits 20-30 are zero"
         */
        const val UNUSED_MASK = 0x7FF00000

        private fun encode(directive: ViaductSchema.Directive): Int {
            var info = 0

            // Bit 0: repeatability
            if (directive.isRepeatable) {
                info = info or REPEATABLE_BIT
            }

            // Bits 1-19: allowed locations
            for (loc in directive.allowedLocations) {
                info = info or (1 shl (LOCATION_START_BIT + loc.ordinal))
            }

            // Bit 31: has arguments
            if (directive.args.isNotEmpty()) {
                info = info or HAS_ARGS_BIT
            }

            return info
        }
    }
}

//
// Constant-definition related constants and data classes

/** Flag for the first word of a list in the compound constants section. */
internal const val IS_LIST_CONSTANT = (1 shl 30)

// These `K_` constants are used to prefix simple constant strings to
// indicate their GraphQL type.  Upper nibble contains the kind code
// (non-zero), lower nibble must be zero.  All values 0x10-0x70 are
// valid single-byte UTF-8 characters, allowing us to treat
// kindCode+stringContent as a Kotlin String during encoding.

/** NullValue - represents GraphQL null */
internal const val K_NULL_VALUE: Int = 0x10

/** IntValue - represents GraphQL Int/Long/Byte/Short (all use IntValue in GraphQL-Java) */
internal const val K_INT_VALUE: Int = 0x20

/** FloatValue - represents GraphQL Float */
internal const val K_FLOAT_VALUE: Int = 0x30

/** StringValue - represents GraphQL String/ID/Date/DateTime/Time/Json */
internal const val K_STRING_VALUE: Int = 0x40

/** BooleanValue - represents GraphQL Boolean */
internal const val K_BOOLEAN_VALUE: Int = 0x50

/** EnumValue - represents GraphQL Enum values */
internal const val K_ENUM_VALUE: Int = 0x60

/** Mask for extracting the kind code from the first byte of a simple constant */
internal const val SIMPLE_CONSTANT_KIND_MASK = 0xF0

/** Low nibble mask for simple constant kind codes (must be zero) */
internal const val SIMPLE_CONSTANT_KIND_LOW_NIBBLE_MASK = 0x0F

/**
 * Represents a list constant value with depth tracking.
 *
 * Depth is calculated as 1 + the maximum depth of any element.
 * For example: [1, 2, 3] has depth 1, [[1], [2]] has depth 2.
 */
data class ListConstant(override val depth: Int, val elements: List<Any?>) : CompoundConstant {
    override val key = elements
}

/**
 * Represents an input object constant value with depth tracking.
 *
 * Depth is calculated as 1 + the maximum depth of any field value.
 * For example: {x: 1, y: 2} has depth 1, {x: {y: 1}} has depth 2.
 */
data class InputObjectConstant(override val depth: Int, val fieldPairs: Map<String, Any?>) : CompoundConstant {
    override val key = fieldPairs
}

/**
 * Base interface for compound constant values (lists and input objects).
 *
 * The depth property enables ordering by nesting level, which will be used
 * for future optimizations in the binary encoding.
 */
sealed interface CompoundConstant : Comparable<CompoundConstant> {
    val depth: Int
    val key: Any?

    override fun compareTo(other: CompoundConstant): Int {
        val depthCmp = depth.compareTo(other.depth)
        if (depthCmp != 0) return depthCmp
        // Tiebreaker: compare by hash code for stable ordering when depths are equal
        return key.hashCode().compareTo(other.key.hashCode())
    }
}

//
// Generic functions for manipulating bit fields

/**
 * Assumes [mask] is a word like 0x0001C000,
 * i.e., some consecutive 1 bits identifying
 * a "field" within a word.  This function
 * will extract the value of that field
 * as an `Int` (ie, shifted right).
 */
inline fun Int.extract(mask: Int): Int = (this and mask) ushr mask.countTrailingZeroBits()

/** Returns true if `this and mask` is zero. */
internal inline fun Int.isZero(mask: Int): Boolean = 0 == (this and mask)

/**
 * Assumes [bitMask] is a word with just one bit set
 * and returns true if that bit is set.
 */
internal inline fun Int.isBitSet(bitMask: Int): Boolean = 0 != (this and bitMask)

/**
 * Calculates padding bytes needed to align to 4-byte boundary.
 * Returns 0 if already aligned, otherwise returns number of padding bytes needed.
 */
internal inline fun paddingTo4(bytes: Int): Int = (4 - (bytes and WORD_ALIGN_MASK)) and WORD_ALIGN_MASK
