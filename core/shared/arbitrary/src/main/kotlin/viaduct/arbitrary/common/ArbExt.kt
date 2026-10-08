@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.Gen
import io.kotest.property.RandomSource
import io.kotest.property.Sample
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choose
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.filterNot
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.pair
import io.kotest.property.arbitrary.shuffle
import io.kotest.property.asSample
import kotlin.math.max
import kotlin.math.min
import viaduct.apiannotations.VisibleForTest

/** Convert an arb to an infinite [kotlin.sequences.Sequence] */
@VisibleForTest
fun <T> Gen<T>.asSequence(rs: RandomSource): Sequence<T> = generate(rs).map { it.value }

/**
 * Flatten this Arb into an Arb of the inner item type.
 * The new Arb will return items in the same order as produced by the original Arb.
 *
 * The underlying Arb must eventually produce a non-empty Iterable; if every sample is empty,
 * [Arb.sample] will loop indefinitely.
 */
@VisibleForTest
fun <T> Arb<Iterable<T>>.flatten(): Arb<T> = Flatten(map { it.iterator() })

/** transform this Arb using [fn], dropping any null values returned by [fn] */
@JvmName("mapNotNull")
@VisibleForTest
fun <T, R> Arb<T>.mapNotNull(fn: (T) -> R?): Arb<R> = map(fn).filter { it != null }.map { it!! }

@VisibleForTest
internal class Flatten<T>(
    val underlying: Arb<Iterator<T>>,
) : Arb<T>() {
    private var chunk: Iterator<T>? = null

    override fun edgecase(rs: RandomSource): T? {
        // Don't interrupt an active chunk — items within a chunk must remain consecutive.
        if (chunk?.hasNext() == true) return null
        val iter = underlying.edgecase(rs) ?: return null
        if (!iter.hasNext()) return null
        chunk = iter
        return chunk!!.next()
    }

    override fun sample(rs: RandomSource): Sample<T> {
        while (chunk == null || chunk?.hasNext() == false) {
            chunk = underlying.sample(rs).value
        }
        return chunk!!.next().asSample()
    }
}

/**
 * Throw a property check failure.
 * The [seed] parameter is included in the error message for reproducibility.
 */
@VisibleForTest
fun failProperty(
    message: String,
    cause: Throwable? = null,
    seed: Long? = null
): Unit =
    throw AssertionError(
        buildString {
            if (seed != null) {
                appendLine("Property failed with seed $seed")
            } else {
                appendLine("Property failed")
            }
            append(message)
        },
        cause
    )

/**
 * Return subsets of a given arb. Subsets will have a size determined by the provided
 * range, or if no range is provided, then subsets will contain between 0 and size-of-the-input-set
 * elements.
 */
fun <T> Arb<Set<T>>.subset(range: IntRange? = null): Arb<Set<T>> =
    flatMap { set ->
        val first = min(max(range?.first ?: 0, 0), set.size)
        val last = min(max(range?.last ?: 0, 0), set.size)

        Arb
            .pair(
                Arb.shuffle(set.toList()),
                Arb.int(first..last)
            ).map { (shuffled, count) ->
                shuffled.take(count).toSet()
            }
    }

/**
 * Return an Arb<Set> describing subsets of this Set.
 * @see Arb<Set<T>>.subset
 */
fun <T> Set<T>.arbSubset(range: IntRange? = null): Arb<Set<T>> = Arb.constant(this).subset(range = range)

/**
 * Filter an Arb<IntRange> to only yield non-empty IntRange values.
 * This is different from [Arb.Companion.intRange], which can yield empty
 * IntRange values.
 */
fun Arb<IntRange>.nonEmpty(): Arb<IntRange> = this.filterNot { it.isEmpty() }

/** Return a new Arb containing only non-null values */
@Suppress("UNCHECKED_CAST")
fun <T> Arb<T?>.filterNotNull(): Arb<T> = filter { it != null }.map { it as T }

/** Generate an Arb that zips values of this arb with the values of another Arb */
fun <T, U> Arb<T>.zip(other: Arb<U>): Arb<Pair<T, U>> = Arb.bind(this, other) { t, u -> t to u }

/**
 * This method is a replacement for [Arb.Companion.choose]. This method will
 * never pick an Arb with a 0 weight. [Arb.Companion.choose] can, via edge cases, select an Arb
 * that is a assigned a weight of 0.
 */
fun <T> Arb.Companion.weightedChoose(
    weightedArb: Pair<Double, Arb<T>>,
    fallbackArb: Arb<T>
): Arb<T> {
    val weight = weightedArb.first
    WeightValidator(weight)?.let { throw IllegalArgumentException(it) }

    return when (weight) {
        0.0 -> fallbackArb
        1.0 -> weightedArb.second
        else -> {
            val intWeight = (weight * 1000).toInt()
            Arb.choose(
                intWeight to weightedArb.second,
                (1000 - intWeight) to fallbackArb
            )
        }
    }
}

/**
 * [Arb.Companion.choose] can, via edge cases, select an Arb that is assigned a weight of 0.
 * This method is a replacement for [Arb.Companion.choose] that will never pick an Arb with a 0 weight.
 */
fun <T> Arb.Companion.weightedChoose(arbs: List<Pair<Double, Arb<T>>>): Arb<T> {
    val weightedArbs = arbs
        .filter { (weight, _) -> weight > 0.0 }
        .map { (weight, arb) -> (weight * 1000).toInt() to arb }

    require(weightedArbs.size > 0)

    return if (weightedArbs.size == 1) {
        weightedArbs.first().second
    } else {
        Arb.choose(
            weightedArbs[0],
            weightedArbs[1],
            *weightedArbs.drop(2).toTypedArray()
        )
    }
}

/** A unit arb, that always returns Unit */
fun Arb.Companion.unit(): Arb<Unit> = Arb.of(Unit)

/**
 * Transform a Collection of Arb<T> into an Arb of List<T>.
 *
 * Example:
 *   val list = listOf(Arb.of(1), Arb.of(2), Arb.of(3))
 *   val items = list.collect().next(rs)   // listOf(1, 2, 3)
 */
fun <T> Collection<Arb<T>>.collect(): Arb<List<T>> = Arb.bind(this.toList()) { it }
