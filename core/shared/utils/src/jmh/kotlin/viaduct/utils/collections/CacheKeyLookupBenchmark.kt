package viaduct.utils.collections

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
open class CacheKeyLookupBenchmark {
    enum class Algorithm { PARALLEL, SEQUENTIAL, ID_PARTITIONED, PARTITIONED }

    enum class KeyType { LONG, STRING, COMPOSITE }

    @Param
    lateinit var algorithm: Algorithm

    @Param("1024")
    var idCount: Int = 0

    @Param("1", "8")
    var variantsPerId: Int = 0

    @Param("COMPOSITE")
    lateinit var keyType: KeyType

    private data class CompositeId(val tenant: String, val id: Long)

    private data class Key(val id: Any, val arguments: Map<String, Any?>)

    private class Cache {
        val entries = ConcurrentHashMap<Key, Key>()
        val partitions = ConcurrentHashMap<Any, ConcurrentHashMap<Key, Key>>()
    }

    private lateinit var cache: Cache
    private lateinit var keys: List<Key>
    private lateinit var exactQueries: List<Key>
    private lateinit var subsetQueries: List<Key>
    private lateinit var incompatibleQueries: List<Key>
    private lateinit var absentQueries: List<Key>
    private var cursor = 0

    @Setup
    fun setup() {
        keys = (0 until idCount).flatMap { id ->
            (0 until variantsPerId).map { variant ->
                Key(identity(id), mapOf("variant" to variant, "__fieldNames" to listOf("id", "name", "email")))
            }
        }.shuffled(Random(12345))
        exactQueries = keys.map { it.copy(arguments = it.arguments.toMap()) }
        subsetQueries = keys.map { it.copy(arguments = it.arguments + ("__fieldNames" to listOf("name"))) }
        incompatibleQueries = keys.map { it.copy(arguments = it.arguments + ("variant" to variantsPerId)) }
        absentQueries = keys.mapIndexed { index, key -> key.copy(id = identity(idCount + index)) }
        cache = Cache()
        keys.forEach { insert(cache, it) }
        for (index in listOf(0, keys.size / 2, keys.lastIndex)) {
            check(lookup(cache, exactQueries[index]) == keys[index])
            check(lookup(cache, subsetQueries[index]) == keys[index])
            check(lookup(cache, incompatibleQueries[index]) == null)
            check(lookup(cache, absentQueries[index]) == null)
        }
    }

    @Benchmark
    fun exactHit(): Any? = lookup(cache, next(exactQueries))

    @Benchmark
    fun subsetHit(): Any? = lookup(cache, next(subsetQueries))

    @Benchmark
    fun sameIdMiss(): Any? = lookup(cache, next(incompatibleQueries))

    @Benchmark
    fun absentIdMiss(): Any? = lookup(cache, next(absentQueries))

    @Benchmark
    fun populateCache(): Any {
        val fresh = Cache()
        for (key in keys) {
            if (algorithm == Algorithm.ID_PARTITIONED || algorithm == Algorithm.PARTITIONED) {
                val partition = fresh.partitions.computeIfAbsent(partitionKey(key)) { ConcurrentHashMap() }
                partition.entries.firstOrNull { matches(key, it.key) }?.value
                    ?: partition.computeIfAbsent(key) { key }
            } else {
                lookup(fresh, key) ?: fresh.entries.computeIfAbsent(key) { key }
            }
        }
        return fresh
    }

    private fun next(queries: List<Key>): Key {
        val key = queries[cursor]
        cursor = (cursor + 1) % queries.size
        return key
    }

    private fun identity(id: Int): Any =
        when (keyType) {
            KeyType.LONG -> id.toLong()
            KeyType.STRING -> "entity:$id"
            KeyType.COMPOSITE -> CompositeId("tenant:${id % 8}", id.toLong())
        }

    private fun lookup(
        cache: Cache,
        key: Key
    ): Key? =
        when (algorithm) {
            Algorithm.PARALLEL -> cache.entries.searchKeys(50) { existing ->
                if (matches(key, existing)) existing else null
            }?.let { cache.entries[it] }
            Algorithm.SEQUENTIAL -> cache.entries.searchKeys(Long.MAX_VALUE) { existing ->
                if (matches(key, existing)) existing else null
            }?.let { cache.entries[it] }
            Algorithm.ID_PARTITIONED, Algorithm.PARTITIONED -> cache.partitions[partitionKey(key)]
                ?.entries?.firstOrNull { matches(key, it.key) }?.value
        }

    private fun insert(
        cache: Cache,
        key: Key
    ): Key =
        when (algorithm) {
            Algorithm.PARALLEL, Algorithm.SEQUENTIAL -> cache.entries.computeIfAbsent(key) { key }
            Algorithm.ID_PARTITIONED, Algorithm.PARTITIONED -> cache.partitions.computeIfAbsent(partitionKey(key)) { ConcurrentHashMap() }
                .computeIfAbsent(key) { key }
        }

    private fun partitionKey(key: Key): Any = if (algorithm == Algorithm.ID_PARTITIONED) key.id else key.copy(arguments = key.arguments.filterKeys { it != "__fieldNames" })

    private fun matches(
        requested: Key,
        cached: Key
    ): Boolean {
        val requestedWithoutFields = requested.copy(arguments = requested.arguments.filterKeys { it != "__fieldNames" })
        val cachedWithoutFields = cached.copy(arguments = cached.arguments.filterKeys { it != "__fieldNames" })
        if (requestedWithoutFields != cachedWithoutFields) return false
        val requestedFields = requested.arguments["__fieldNames"] as? List<*> ?: emptyList<Any>()
        val cachedFields = (cached.arguments["__fieldNames"] as? List<*>)?.toSet() ?: emptySet<Any>()
        return requestedFields.all { it in cachedFields }
    }
}
