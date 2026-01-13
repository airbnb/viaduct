package viaduct.graphql.schema.binary

/**
 * Immutable mapping from LATIN1 (ie, 8-bit) (not-empty) strings to integer indices.
 *
 * The mapping is dense: the indices are guaranteed to be in range [0, size).
 *
 * The mapping is sorted: for two strings in the table A < B,
 * the corresponding indices are get(A) < get(B).
 */
interface IdentifierTable {
    /** Number of strings in the mapping. */
    val size: Int

    /** Get index for string. Throws [NoSuchElementException] if not found. */
    fun get(key: String): Int

    /** Get index for string. Returns null if not found. */
    fun find(key: String): Int?

    /** Get string by index. Throws [IndexOutOfBoundsException] if invalid. */
    fun keyAt(index: Int): String

    /**
     * Find indices of strings starting with a prefix. Because tables are
     * dense and sorted, the result can be expressed as an integer range.
     *
     * @param prefix The prefix to search for
     * @return A [LookupResult] describing the matching range, or null if no strings match the prefix
     */
    fun lookup(prefix: String): LookupResult?

    /**
     * Result of a prefix lookup operation.
     *
     * This class implements [Iterable] for iteration, [ClosedRange] for range operations,
     * and provides similar semantics to [IntProgression].
     *
     * @property start The start index (inclusive) of matching strings
     * @property endExclusive The end index (exclusive) of matching strings
     * @property exactMatch True if the prefix itself is a string in the table
     */
    class LookupResult(
        override val start: Int,
        val endExclusive: Int,
        val exactMatch: Boolean
    ) : Iterable<Int>, ClosedRange<Int> {
        override val endInclusive: Int
            get() = endExclusive - 1

        override fun isEmpty(): Boolean = start >= endExclusive

        override fun contains(value: Int): Boolean = value in start until endExclusive

        override fun iterator(): Iterator<Int> = (start until endExclusive).iterator()

        /** Returns the indices as an [IntProgression]. */
        fun asProgression(): IntProgression = start until endExclusive

        /** Returns the indices as a [List]. */
        fun toList(): List<Int> = (start until endExclusive).toList()
    }
}
