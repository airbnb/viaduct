package viaduct.utils.collections

/**
 * A Set backed by a BitVector, supporting efficient set intersections
 * @see intersect
 */
class MaskedSet<T> private constructor(
    /** value: the index into [exclude] */
    internal val valueToIndex: Map<T, Int>,
    /**
     * A bitmask describing which elements of [valueToIndex] have been excluded.
     * Excluded values will not be returned by [toList].
     */
    internal val exclude: BitVector,
    /**
     * The current size of this MaskedSet, equal to the size of [valueToIndex]
     * minus the number of bits set in [exclude]
     */
    val size: Int
) : Iterable<T> {
    companion object {
        /** An empty [MaskedSet] */
        val empty: MaskedSet<Nothing> = MaskedSet(emptyMap(), BitVector(0), 0)

        @Suppress("UNCHECKED_CAST")
        fun <T> empty(): MaskedSet<T> = empty as MaskedSet<T>

        /** create a new [MaskedSet] from a collection of values */
        operator fun <T> invoke(values: Iterable<T>): MaskedSet<T> {
            val map = LinkedHashMap<T, Int>()
            var i = 0
            values.forEach { v ->
                if (v !in map) {
                    // only increment i for values written to valueToIndex
                    map.put(v, i++)
                }
            }
            val exclude = BitVector(i)
            return MaskedSet(map, exclude, map.size)
        }
    }

    /** returns true if this [MaskedSet] contains no items */
    fun isEmpty(): Boolean = size == 0

    /**
     * Returns an iterator over the non-excluded elements in this MaskedSet.
     * The iteration order matches the insertion order of the original values.
     * This implementation uses a custom iterator to avoid intermediate allocations.
     */
    override fun iterator(): Iterator<T> =
        object : Iterator<T> {
            private val entryIterator = valueToIndex.iterator()
            private var nextValue: T? = null
            private var hasNext = false

            init {
                advance()
            }

            private fun advance() {
                while (entryIterator.hasNext()) {
                    val (value, index) = entryIterator.next()
                    if (!exclude.get(index)) {
                        nextValue = value
                        hasNext = true
                        return
                    }
                }
                hasNext = false
                nextValue = null
            }

            override fun hasNext(): Boolean = hasNext

            override fun next(): T {
                if (!hasNext) throw NoSuchElementException()
                val result = nextValue
                advance()
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
        }

    /**
     * Return a new [MaskedSet] that is the logical intersection of this and [other]
     * The returned MaskedSet will retain the collection ordering of the current object.
     */
    fun intersect(other: MaskedSet<T>): MaskedSet<T> {
        // Implementation note:
        // This implementation tends to be faster than a simple collection-based intersection because
        // it uses structural sharing on [valueToIndex]. Deriving an intersecting MaskedSet will reuse
        // the same collection without creating a filtered copy.
        // Instead, we copy and modify [exclude] BitVector, which is significantly less expensive.

        if (this === other) return this
        if (isEmpty() || other.isEmpty()) return empty()

        // Iterate the smaller set for speed, but build an exclude bitmap for *this*
        val smaller = if (this.size <= other.size) this else other
        val larger = if (smaller === this) other else this

        // start fully excluded; we'll clear the bits that we decide to include
        val newExclude = BitVector(-valueToIndex.size)
        var newSize = 0

        smaller.valueToIndex.forEach { (v, si) ->
            if (!smaller.exclude.get(si)) {
                val li = larger.valueToIndex[v]
                if (li != null && !larger.exclude.get(li)) {
                    newExclude.clear(valueToIndex[v]!!)
                    newSize++
                }
            }
        }
        return if (newSize == 0) empty() else MaskedSet(valueToIndex, newExclude, newSize)
    }

    /**
     * Returns true if [other] is a MaskedSet and its [toList] value is
     * equal to this object's toList value.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MaskedSet<*>) return false

        return toList() == other.toList()
    }

    override fun hashCode(): Int = this.toList().hashCode()
}
