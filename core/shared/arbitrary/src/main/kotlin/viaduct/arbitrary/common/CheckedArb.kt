@file:Suppress("ForbiddenImport", "TooGenericExceptionCaught")

package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.PropertyContext
import io.kotest.property.PropertyTesting
import io.kotest.property.RandomSource
import io.kotest.property.Sample
import io.kotest.property.arbitrary.next
import io.kotest.property.checkAll
import java.io.PrintStream
import kotlinx.coroutines.runBlocking

/**
 * An Arb combined with an assertion, providing a common interface for checking all values,
 * finding the minimum violating value, and seed marching
 */
class CheckedArb<T>(
    private val underlying: Arb<T>,
    private val check: (T) -> Unit
) : Arb<T>() {
    override fun edgecase(rs: RandomSource): T? = underlying.edgecase(rs)?.also { check(it) }

    override fun sample(rs: RandomSource): Sample<T> = underlying.sample(rs).also { check(it.value) }

    /** Apply [check] to every sample within [iterations] samples. */
    fun checkAll(iterations: Int = PropertyTesting.defaultIterationCount): PropertyContext =
        runBlocking {
            underlying.checkAll(iterations) { check(it) }
        }

    /** Return the minimum [Violation] within [iter] samples, if one exists */
    fun minViolation(
        comparator: Comparator<T>,
        rs: RandomSource,
        iter: Int = PropertyTesting.defaultIterationCount,
        printEvery: Int = 1_000,
        out: PrintStream = System.out
    ): Violation<T>? =
        underlying.asSequence(rs)
            .take(iter)
            .foldIndexed(null as Violation<T>?) { i, acc, t ->
                if (printEvery > 0 && i.mod(printEvery) == 0) {
                    out.println("Iteration $i...")
                }
                try {
                    check(t)
                    acc
                } catch (err: Throwable) {
                    if (acc == null) {
                        out.println("Found new min violation at iteration $i")
                        Violation(t, err, rs.seed)
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val accValue = acc.value as T
                        if (comparator.compare(t, accValue) < 0) {
                            out.println("Found new min violation at iteration $i")
                            Violation(t, err, rs.seed)
                        } else {
                            acc
                        }
                    }
                }
            }

    /** Return the first [Violation] within [maxIter] samples, if one exists */
    fun seedMarch(
        startingSeed: Long = 0,
        maxIter: Int = 100_000_000,
        printEvery: Int = 1_000,
        out: PrintStream = System.out
    ): Violation<T>? {
        var iter = 0
        while (iter < maxIter) {
            val seed = startingSeed + iter
            if (printEvery > 0 && iter.mod(printEvery) == 0) {
                out.println("Seed $seed...")
            }
            var t: T? = null
            try {
                val sample = underlying.next(RandomSource.seeded(seed))
                t = sample
                check(sample)
            } catch (e: Throwable) {
                return Violation(t, e, seed)
            }
            iter += 1
        }
        return null
    }
}

/** [value] is null when generation fails before a sample is produced. */
data class Violation<T>(val value: T?, val err: Throwable, val seed: Long)

/** return a [CheckedArb] that applies [check] to every sample */
fun <T> Arb<T>.withCheck(check: (T) -> Unit): CheckedArb<T> = CheckedArb(this, check)
