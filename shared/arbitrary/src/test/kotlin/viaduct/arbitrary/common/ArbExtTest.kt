@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.char
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.take
import io.kotest.property.exhaustive.exhaustive
import io.kotest.property.forAll
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ArbExtTest : KotestPropertyBase() {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `Gen_asSequence`(): Unit =
        runBlocking {
            Arb.list(Arb.int(), 0..10).map { list ->
                val arb = Arb.constant(list)
                val seq = arb.asSequence(RandomSource.default())
                arb to seq
            }.forAll { (arb, seq) ->
                seq.first() == arb.next(randomSource())
            }
        }

    @Test
    fun `Gen_minViolation -- can succeed`() {
        val failure = Arb.int(-10..10).minViolation(Comparator.naturalOrder()) { true }
        assertNull(failure)
    }

    @Test
    fun `Gen_minViolation -- returns violation`() {
        val failure = Arb.int(-10..10).minViolation(Comparator.naturalOrder()) { it > 0 }
        assertEquals(-10, failure)
    }

    @Test
    fun `Gen_minViolation -- exhaustive`() {
        val falsifier = (-10..10)
            .toList()
            .exhaustive()
            .minViolation(Comparator.naturalOrder()) { it > 0 }
        assertEquals(-10, falsifier)
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun `Arb_flatten`(): Unit =
        runBlocking {
            val chunkSize = 10
            val arb = Arb.int(0..10).map { i ->
                Arb.char('a'..'z')
                    .map { c -> i to c }
                    .take(chunkSize, randomSource())
                    .toList()
            }.flatten()

            arb.take(chunkSize * 1_000, randomSource())
                .chunked(chunkSize)
                .forEach { chunk ->
                    val firsts = chunk.map { it.first }
                    assertEquals(1, firsts.distinct().size)
                }
        }

    @Test
    fun `Any_failProperty -- failure message includes seed values`() {
        // no seed value is provided, use the current randomSource
        assertThrows<AssertionError> {
            failProperty("msg")
        }.let {
            assertTrue(it.message?.contains(randomSource.seed.toString()) ?: false)
        }

        // provide a seed value
        val seed = UUID.randomUUID().leastSignificantBits
        assertThrows<AssertionError> {
            failProperty("msg", seed = seed)
        }.let {
            assertTrue(it.message?.contains(seed.toString()) ?: false)
        }
    }
}
