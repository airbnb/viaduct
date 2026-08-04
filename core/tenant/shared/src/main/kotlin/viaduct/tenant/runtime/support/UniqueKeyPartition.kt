package viaduct.tenant.runtime.support

import viaduct.apiannotations.InternalApi

/**
 * Partitions [values] into the minimum number of stable groups where [keySelector] is unique
 * within each group.
 */
@InternalApi
fun <T, K> partitionByUniqueKey(
    values: List<T>,
    keySelector: (T) -> K,
): List<List<T>> {
    val groups = mutableListOf<MutableList<T>>()
    val occurrences = mutableMapOf<K, Int>()

    values.forEach { value ->
        val key = keySelector(value)
        val groupIndex = occurrences.getOrDefault(key, 0)
        if (groupIndex == groups.size) {
            groups.add(mutableListOf())
        }
        groups[groupIndex].add(value)
        occurrences[key] = groupIndex + 1
    }

    return groups
}
