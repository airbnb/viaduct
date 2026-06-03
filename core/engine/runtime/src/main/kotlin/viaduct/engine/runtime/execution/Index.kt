package viaduct.engine.runtime.execution

/**
 * An Index is a key-value map that uses structural sharing for
 * efficient composition
 *
 * Performance:
 *  - `merge` of 2 indexes runs in constant time
 *  - `find` in an [Index] runs in amortized constant time (first time is linear)
 */
sealed interface Index<K, V> {
    /** Returns the value for [k], or `null` if [k] is not indexed. */
    fun find(k: K): V?

    /** @see [find] */
    operator fun get(k: K): V? = find(k)

    /** Returns an index that checks [overrides] before this index. */
    fun merge(overrides: Index<K, V>): Index<K, V>

    /** @see merge */
    operator fun plus(overrides: Index<K, V>): Index<K, V> = this.merge(overrides)

    companion object {
        /** Returns an index with no entries. */
        @Suppress("UNCHECKED_CAST")
        fun <K, V> empty(): Index<K, V> = Empty as Index<K, V>

        /** create a new [Index.Builder] */
        fun <K, V> builder(): Builder<K, V> = Builder()

        /** Create a single-item [Index] */
        fun <K, V> single(
            k: K,
            v: V
        ): Index<K, V> = Single(k, v)
    }

    class Builder<K, V> {
        private var index: Index<K, V> = empty()

        fun add(overrides: Index<K, V>): Builder<K, V> {
            index = index.merge(overrides)
            return this
        }

        fun add(
            k: K,
            v: V
        ): Builder<K, V> = add(Single(k, v))

        fun build(): Index<K, V> = index
    }

    private object Empty : Index<Any?, Any?> {
        override fun find(k: Any?): Any? = null

        override fun merge(overrides: Index<Any?, Any?>): Index<Any?, Any?> = overrides
    }

    private class Single<K, V>(val k: K, val v: V) : Index<K, V> {
        override fun find(k: K): V? =
            if (this.k == k) {
                v
            } else {
                null
            }

        override fun merge(overrides: Index<K, V>): Index<K, V> {
            return if (overrides === Empty) {
                this
            } else {
                Composite(this, overrides)
            }
        }
    }

    private class Composite<K, V>(
        private val base: Index<K, V>,
        private val overrides: Index<K, V>
    ) : Index<K, V> {
        @Volatile
        private var flattenedMap: Map<K, V>? = null

        override fun find(k: K): V? {
            val cached = flattenedMap
            if (cached != null) {
                return cached[k]
            }

            val map = buildMap {
                putEntries(base)
                putEntries(overrides)
            }
            flattenedMap = map
            return map[k]
        }

        override fun merge(overrides: Index<K, V>): Index<K, V> {
            return if (overrides === Empty) {
                this
            } else {
                Composite(this, overrides)
            }
        }

        private fun MutableMap<K, V>.putEntries(index: Index<K, V>) {
            if (index === Empty) {
                return
            }
            when (index) {
                is Single -> this[index.k] = index.v
                is Composite -> {
                    putEntries(index.base)
                    putEntries(index.overrides)
                }
                else -> Unit
            }
        }
    }
}
