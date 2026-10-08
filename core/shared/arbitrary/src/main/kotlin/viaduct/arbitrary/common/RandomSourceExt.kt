package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.delay

/**
 * Get a boolean from this random source, with probability of a true
 * value being equal to the provided weight. Weights 0 and 1 consume no randomness.
 */
fun RandomSource.sampleWeight(weight: Double): Boolean =
    when (weight) {
        0.0 -> false
        1.0 -> true
        else -> random.nextDouble(0.0, 1.0) <= weight
    }

/**
 * Return an integer describing how many times the provided [CompoundingWeight]
 * was sampled before it hit its `max` sample count or returned false.
 */
fun RandomSource.count(weight: CompoundingWeight): Int {
    tailrec fun loop(count: Int): Int =
        if (count == weight.max) {
            count
        } else if (!sampleWeight(weight.weight)) {
            count
        } else {
            loop(count + 1)
        }
    return loop(0)
}

/** suspend for a value of milliseconds bounded by  [latencyMillis] */
internal suspend fun RandomSource.maybeDelay(latencyMillis: LongRange) {
    if (latencyMillis.last > 0) {
        val latencyMs = Arb.long(latencyMillis).next(this)
        delay(latencyMs)
    }
}

fun RandomSource.fork(): RandomSource = RandomSource.seeded(random.nextLong())
