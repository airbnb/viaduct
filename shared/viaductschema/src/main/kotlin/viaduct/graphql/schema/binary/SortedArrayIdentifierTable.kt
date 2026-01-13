package viaduct.graphql.schema.binary

/**
 * An [IdentifierTable] implementation backed by a sorted array of strings.
 *
 * Uses binary search for O(log n) lookup operations.
 */
class SortedArrayIdentifierTable private constructor(
    private val sortedStrings: Array<String>
) : IdentifierTable {
    override val size: Int = sortedStrings.size

    override fun get(key: String): Int = find(key) ?: throw NoSuchElementException("\"$key\" not in identifier table.")

    override fun find(key: String): Int? {
        val idx = sortedStrings.binarySearch(key)
        return if (idx >= 0) idx else null
    }

    override fun keyAt(index: Int): String = sortedStrings[index]

    override fun lookup(prefix: String): IdentifierTable.LookupResult? {
        val idx = sortedStrings.binarySearch(prefix)
        val start = if (idx < 0) -idx - 1 else idx

        // Find end by searching for prefix + max char
        val endIdx = sortedStrings.binarySearch(prefix + '\uFFFF')
        val endExclusive = if (endIdx < 0) -endIdx - 1 else endIdx

        if (start == endExclusive) return null
        return IdentifierTable.LookupResult(start, endExclusive, idx >= 0)
    }

    companion object {
        /**
         * Creates an [IdentifierTable] from an iterable of strings.
         *
         * @param strings The strings to include in the table (will be sorted and deduplicated)
         * @return A new [SortedArrayIdentifierTable]
         */
        fun fromStrings(strings: Iterable<String>): SortedArrayIdentifierTable {
            val sorted = strings.toSortedSet().toTypedArray()
            return SortedArrayIdentifierTable(sorted)
        }

        /**
         * Writes the identifier table strings to an output stream.
         *
         * Format:
         * - Strings: Null-terminated ASCII strings, sorted
         *
         * @param sortedStrings Deduplicated, sorted set of strings in the table
         * @param out The output stream to write to
         */
        internal fun write(
            sortedStrings: Iterable<String>,
            out: BOutputStream
        ) {
            for (s in sortedStrings) {
                out.writeIdentifier(s)
            }
        }

        /**
         * Reads an identifier table from an input stream.
         *
         * @param data An input stream positioned to the start of data written by the [write] function
         * @param count The number of identifiers to read (from file header)
         * @return A new [SortedArrayIdentifierTable]
         */
        internal fun read(
            data: BInputStream,
            count: Int
        ): SortedArrayIdentifierTable {
            val strings = Array(count) { data.readIdentifier() }
            return SortedArrayIdentifierTable(strings)
        }
    }
}
