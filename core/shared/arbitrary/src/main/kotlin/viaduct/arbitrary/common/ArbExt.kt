@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.Gen
import io.kotest.property.RandomSource
import io.kotest.property.Sample
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.asSample
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
