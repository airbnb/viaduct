package viaduct.utils.memoize

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

private const val DEFAULT_CAPACITY = 256

fun <A, R> ((A) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, R> ((A) -> R).memoize(cache: MutableMap<A, R>): (A) -> R = memoize(cache) { it }

fun <A, K, R> ((A) -> R).memoize(
    cache: MutableMap<K, R>,
    keyMapper: (A) -> K
): (A) -> R =
    { a ->
        cache.getOrPutMemoized(keyMapper(a)) { this(a) }
    }

fun <A, B, R> ((A, B) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, R> ((A, B) -> R).memoize(cache: MutableMap<Pair<A, B>, R>): (A, B) -> R = memoize(cache) { a, b -> Pair(a, b) }

fun <A, B, K, R> ((A, B) -> R).memoize(
    cache: MutableMap<K, R>,
    keyMapper: (A, B) -> K
): (A, B) -> R =
    { a, b ->
        cache.getOrPutMemoized(keyMapper(a, b)) { this(a, b) }
    }

fun <A, B, C, R> ((A, B, C) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, R> ((A, B, C) -> R).memoize(cache: MutableMap<Triple<A, B, C>, R>): (A, B, C) -> R = memoize(cache) { a, b, c -> Triple(a, b, c) }

fun <A, B, C, K, R> ((A, B, C) -> R).memoize(
    cache: MutableMap<K, R>,
    keyMapper: (A, B, C) -> K
): (A, B, C) -> R =
    { a, b, c ->
        cache.getOrPutMemoized(keyMapper(a, b, c)) { this(a, b, c) }
    }

fun <A, B, C, D, R> ((A, B, C, D) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, R> ((A, B, C, D) -> R).memoize(cache: MutableMap<Quadruple<A, B, C, D>, R>): (A, B, C, D) -> R = memoize(cache) { a, b, c, d -> Quadruple(a, b, c, d) }

fun <A, B, C, D, K, R> ((A, B, C, D) -> R).memoize(
    cache: MutableMap<K, R>,
    keyMapper: (A, B, C, D) -> K
): (A, B, C, D) -> R =
    { a, b, c, d ->
        cache.getOrPutMemoized(keyMapper(a, b, c, d)) { this(a, b, c, d) }
    }

fun <A, B, C, D, E, R> ((A, B, C, D, E) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D, E) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, E, R> ((A, B, C, D, E) -> R).memoize(cache: MutableMap<Quintuple<A, B, C, D, E>, R>): (A, B, C, D, E) -> R = memoize(cache) { a, b, c, d, e -> Quintuple(a, b, c, d, e) }

fun <A, B, C, D, E, K, R> ((A, B, C, D, E) -> R).memoize(
    cache: MutableMap<K, R>,
    keyMapper: (A, B, C, D, E) -> K
): (A, B, C, D, E) -> R =
    { a, b, c, d, e ->
        cache.getOrPutMemoized(keyMapper(a, b, c, d, e)) { this(a, b, c, d, e) }
    }

fun <A, B, C, D, E, F, R> ((A, B, C, D, E, F) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D, E, F) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, E, F, R> ((A, B, C, D, E, F) -> R).memoize(cache: MutableMap<Sextuple<A, B, C, D, E, F>, R>): (A, B, C, D, E, F) -> R =
    memoize(cache) { a, b, c, d, e, f -> Sextuple(a, b, c, d, e, f) }

fun <A, B, C, D, E, F, K, R> ((A, B, C, D, E, F) -> R).memoize(
    cache: MutableMap<K, R>,
    keyMapper: (A, B, C, D, E, F) -> K
): (A, B, C, D, E, F) -> R =
    { a, b, c, d, e, f ->
        cache.getOrPutMemoized(keyMapper(a, b, c, d, e, f)) { this(a, b, c, d, e, f) }
    }

fun <A, B, C, D, E, F, G, R> ((A, B, C, D, E, F, G) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D, E, F, G) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, E, F, G, R> ((A, B, C, D, E, F, G) -> R).memoize(cache: MutableMap<Septuple<A, B, C, D, E, F, G>, R>): (A, B, C, D, E, F, G) -> R =
    memoize(cache) { a, b, c, d, e, f, g -> Septuple(a, b, c, d, e, f, g) }

fun <A, B, C, D, E, F, G, K, R> ((A, B, C, D, E, F, G) -> R).memoize(
    cache: MutableMap<K, R>,
    keyMapper: (A, B, C, D, E, F, G) -> K
): (A, B, C, D, E, F, G) -> R =
    { a, b, c, d, e, f, g ->
        cache.getOrPutMemoized(keyMapper(a, b, c, d, e, f, g)) { this(a, b, c, d, e, f, g) }
    }

// Select the concurrent getOrPut overload even when the cache is typed as MutableMap.
private inline fun <K, V> MutableMap<K, V>.getOrPutMemoized(
    key: K,
    defaultValue: () -> V,
): V =
    if (this is ConcurrentMap<K, V>) {
        getOrPut(key, defaultValue)
    } else {
        getOrPut(key, defaultValue)
    }
