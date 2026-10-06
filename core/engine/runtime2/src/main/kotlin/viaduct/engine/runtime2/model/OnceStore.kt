package viaduct.engine.runtime2.model

import java.util.concurrent.ConcurrentHashMap

/**
 * A thread-safe store in which each key can be written exactly once.
 *
 * Absence is distinct from a stored null value when [V] is nullable. Reads of absent keys and
 * repeated writes are outside the store's domain.
 */
internal class OnceStore<K : Any, V> {
    private val values = ConcurrentHashMap<K, Any>()

    constructor()

    constructor(initialValues: Map<K, V>) {
        initialValues.forEach { (key, value) ->
            values[key] = storedValue(value)
        }
    }

    fun isSet(key: K): Boolean = values.containsKey(key)

    /** @throws IllegalStateException when [key] has not been written */
    fun read(key: K): V {
        val storedValue = values[key]
        check(storedValue != null) { "$key not found" }
        return value(storedValue)
    }

    /** @throws IllegalStateException when [key] has already been written */
    fun write(
        key: K,
        value: V,
    ) {
        val previous = values.putIfAbsent(key, storedValue(value))
        check(previous == null) { "$key already written" }
    }

    /** Returns a stable copy of the values written before or during this observation. */
    fun snapshot(): Map<K, V> = values.mapValues { (_, storedValue) -> value(storedValue) }

    private fun storedValue(value: V): Any = value ?: NULL_PROXY

    @Suppress("UNCHECKED_CAST")
    private fun value(storedValue: Any): V = if (storedValue === NULL_PROXY) null as V else storedValue as V

    private data object NULL_PROXY
}
