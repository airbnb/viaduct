package viaduct.utils.memoize

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

private const val DEFAULT_CAPACITY = 256

fun <A, R> ((A) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, R> ((A) -> R).memoize(cache: ConcurrentMap<A, R>): (A) -> R = memoize(cache) { it }

fun <A, K, R> ((A) -> R).memoize(
    cache: ConcurrentMap<K, R>,
    keyMapper: (A) -> K
): (A) -> R =
    { a: A ->
        cache.getOrPut(keyMapper(a)) { this(a) }
    }

fun <A, B, R> ((A, B) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, R> ((A, B) -> R).memoize(cache: ConcurrentMap<Pair<A, B>, R>): (A, B) -> R = memoize(cache) { a, b -> a to b }

fun <A, B, K : Pair<*, *>, R> ((A, B) -> R).memoize(
    cache: ConcurrentMap<K, R>,
    keyMapper: (A, B) -> K
): (A, B) -> R =
    { a: A, b: B ->
        cache.getOrPut(keyMapper(a, b)) { this(a, b) }
    }

fun <A, B, C, R> ((A, B, C) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C) -> R =
    memoize(
        ConcurrentHashMap(initialCapacity)
    ) { a, b, c -> Triple(a, b, c) }

fun <A, B, C, R> ((A, B, C) -> R).memoize(cache: ConcurrentMap<Triple<A, B, C>, R>): (A, B, C) -> R = memoize(cache) { a, b, c -> Triple(a, b, c) }

fun <A, B, C, K : Triple<*, *, *>, R> ((A, B, C) -> R).memoize(
    cache: ConcurrentMap<K, R>,
    keyMapper: (A, B, C) -> K
): (A, B, C) -> R =
    { a: A, b: B, c: C ->
        cache.getOrPut(keyMapper(a, b, c)) { this(a, b, c) }
    }

fun <A, B, C, D, R> ((A, B, C, D) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, R> ((A, B, C, D) -> R).memoize(cache: ConcurrentMap<Quadruple<A, B, C, D>, R>): (A, B, C, D) -> R = memoize(cache) { a, b, c, d -> Quadruple(a, b, c, d) }

fun <A, B, C, D, K : Quadruple<*, *, *, *>, R> ((A, B, C, D) -> R).memoize(
    cache: ConcurrentMap<K, R>,
    keyMapper: (A, B, C, D) -> K
): (A, B, C, D) -> R =
    { a: A, b: B, c: C, d: D ->
        cache.getOrPut(keyMapper(a, b, c, d)) { this(a, b, c, d) }
    }

fun <A, B, C, D, E, R> ((A, B, C, D, E) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D, E) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, E, R> ((A, B, C, D, E) -> R).memoize(cache: ConcurrentMap<Quintuple<A, B, C, D, E>, R>): (A, B, C, D, E) -> R = memoize(cache) { a, b, c, d, e -> Quintuple(a, b, c, d, e) }

fun <A, B, C, D, E, K : Quintuple<*, *, *, *, *>, R> ((A, B, C, D, E) -> R).memoize(
    cache: ConcurrentMap<K, R>,
    keyMapper: (A, B, C, D, E) -> K
): (A, B, C, D, E) -> R =
    { a: A, b: B, c: C, d: D, e: E ->
        cache.getOrPut(keyMapper(a, b, c, d, e)) { this(a, b, c, d, e) }
    }

fun <A, B, C, D, E, F, R> ((A, B, C, D, E, F) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D, E, F) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, E, F, R> ((A, B, C, D, E, F) -> R).memoize(cache: ConcurrentMap<Sextuple<A, B, C, D, E, F>, R>): (A, B, C, D, E, F) -> R =
    { a: A, b: B, c: C, d: D, e: E, f: F ->
        cache.getOrPut(Sextuple(a, b, c, d, e, f)) { this(a, b, c, d, e, f) }
    }

fun <A, B, C, D, E, F, G, R> ((A, B, C, D, E, F, G) -> R).memoize(initialCapacity: Int = DEFAULT_CAPACITY): (A, B, C, D, E, F, G) -> R = memoize(ConcurrentHashMap(initialCapacity))

fun <A, B, C, D, E, F, G, R> ((A, B, C, D, E, F, G) -> R).memoize(cache: ConcurrentMap<Septuple<A, B, C, D, E, F, G>, R>): (A, B, C, D, E, F, G) -> R =
    { a: A, b: B, c: C, d: D, e: E, f: F, g: G ->
        cache.getOrPut(Septuple(a, b, c, d, e, f, g)) { this(a, b, c, d, e, f, g) }
    }
