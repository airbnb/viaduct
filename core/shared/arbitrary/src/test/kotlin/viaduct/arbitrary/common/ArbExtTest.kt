@file:Suppress("ForbiddenImport")
@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.char
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.take
import io.kotest.property.exhaustive.exhaustive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
    fun `Gen_asSequence`(): Unit =
        runBlocking {
            Arb
                .list(Arb.int(), 0..10)
                .map { list ->
                    val arb = Arb.constant(list)
                    val seq = arb.asSequence(RandomSource.default())
                    arb to seq
                }.forAll { (arb, seq) ->
                    seq.first() == arb.next(randomSource)
                }
        }

    @Test
    fun `Gen_minViolation -- can succeed`() {
        val failure = Arb.int(-10..10).minViolation(Comparator.naturalOrder(), randomSource) { true }
        assertNull(failure)
    }

    @Test
    fun `Gen_minViolation -- returns violation`() {
        val failure = Arb.int(-10..10).minViolation(Comparator.naturalOrder(), randomSource) { it > 0 }
        assertEquals(-10, failure)
    }

    @Test
    fun `Gen_minViolation -- exhaustive`() {
        val falsifier = (-10..10)
            .toList()
            .exhaustive()
            .minViolation(Comparator.naturalOrder(), randomSource) { it > 0 }
        assertEquals(-10, falsifier)
    }

    @Test
    fun `Arb_flatten`(): Unit =
        runBlocking {
            val chunkSize = 10
            val arb = Arb
                .int(0..10)
                .map { i ->
                    Arb
                        .char('a'..'z')
                        .map { c -> i to c }
                        .take(chunkSize, randomSource)
                        .toList()
                }.flatten()

            arb
                .take(chunkSize * 1_000, randomSource)
                .chunked(chunkSize)
                .forEach { chunk ->
                    val firsts = chunk.map { it.first }
                    assertEquals(1, firsts.distinct().size)
                }
        }

    @Test
    fun `Any_failProperty -- failure message includes seed when provided`() {
        // no seed value is provided
        assertThrows<AssertionError> {
            failProperty("msg")
        }.let {
            assertTrue(it.message?.contains("Property failed") ?: false)
            assertTrue(it.message?.contains("msg") ?: false)
        }

        // provide a seed value
        val explicitSeed = UUID.randomUUID().leastSignificantBits
        assertThrows<AssertionError> {
            failProperty("msg", seed = explicitSeed)
        }.let {
            assertTrue(it.message?.contains(explicitSeed.toString()) ?: false)
        }
    }

    @Test
    fun `Arb_flatten -- items within a chunk are consecutive`() {
        val arb = Arb.of(listOf(1, 1), listOf(2, 2)).flatten()
        val values = arb.asSequence(randomSource).take(4).toList()
        // each pair of consecutive items must come from the same chunk
        assertEquals(values[0], values[1])
        assertEquals(values[2], values[3])
    }

    @Test
    fun `Arb_flatten -- skips empty chunks`() {
        // Alternates between empty and non-empty lists; flatten must not throw
        val arb = Arb.of(emptyList(), listOf("a"), emptyList(), listOf("b")).flatten()
        val values = arb.asSequence(randomSource).take(10).toList()
        assertTrue(values.all { it == "a" || it == "b" })
    }

    @Test
    fun `mapNotNull drops nulls`() {
        val arb = Arb.of(1, 2, 3, 4, 5).mapNotNull { if (it % 2 == 0) it else null }
        val values = arb.asSequence(randomSource).take(100).toSet()
        assertEquals(setOf(2, 4), values)
    }

    @Test
    fun `seedMarch returns null when no failure`() {
        val result = Arb.int().seedMarch(maxIter = 100)
        assertNull(result)
    }

    @Test
    fun `printSeedMarch prints seed progress`() {
        val baos = ByteArrayOutputStream()
        val out = PrintStream(baos)
        Arb.int().printSeedMarch(maxIter = 10, printEvery = 5, out = out)
        val output = baos.toString()
        assertTrue(output.contains("Seed 0"))
        assertTrue(output.contains("Seed 5"))
    }
}
