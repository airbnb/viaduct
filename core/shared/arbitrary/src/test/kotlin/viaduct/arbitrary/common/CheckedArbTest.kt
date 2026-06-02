package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.Sample
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.asSample
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class CheckedArbTest : KotestPropertyBase() {
    @Test
    fun `checkAll -- succeeds`() {
        assertDoesNotThrow {
            Arb.constant(1).withCheck({}).checkAll()
        }
    }

    @Test
    fun `checkAll -- fails`() {
        val err = RuntimeException()
        val err2 = assertThrows<AssertionError> {
            Arb.constant(1)
                .withCheck { throw err }
                .checkAll()
        }
        assertSame(err, err2.cause)
    }

    @Test
    fun `minViolation -- succeeds`() {
        val mv = Arb.constant(1)
            .withCheck {}
            .minViolation(Comparator.naturalOrder(), randomSource)
        assertNull(mv)
    }

    @Test
    fun `minViolation -- fails`() {
        val err = RuntimeException()
        val mv = Arb.int(0..5)
            .withCheck { i ->
                if (i == 0) throw err
            }
            .minViolation(Comparator.naturalOrder(), randomSource)
        assertEquals(Violation(0, err, seed), mv)
    }

    @Test
    fun `minViolation -- honors negative comparator results`() {
        val err = RuntimeException()
        val values = listOf(4, 2, 1)
        val arb = object : Arb<Int>() {
            private var index = 0

            override fun edgecase(rs: RandomSource): Int? = null

            override fun sample(rs: RandomSource): Sample<Int> = values[index++].asSample()
        }
        val mv = arb.withCheck { throw err }
            .minViolation(
                comparator = Comparator { lhs, rhs ->
                    when {
                        lhs < rhs -> -2
                        lhs > rhs -> 2
                        else -> 0
                    }
                },
                rs = randomSource,
                iter = values.size
            )
        assertEquals(Violation(1, err, seed), mv)
    }

    @Test
    fun `seedMarch -- succeeds`() {
        val result = Arb.int(0..100)
            .withCheck {}
            .seedMarch(maxIter = iterations, printEvery = -1)
        assertNull(result)
    }

    @Test
    fun `seedMarch -- fails`() {
        var countDown = 3
        val err = RuntimeException()
        val result = Arb.constant(1)
            .withCheck {
                if (countDown-- == 0) {
                    throw err
                }
            }
            .seedMarch(maxIter = iterations, printEvery = -1)
        assertEquals(Violation(1, err, 3), result)
    }

    @Test
    fun `seedMarch -- returns generator failures with seed`() {
        val err = RuntimeException()
        val arb = object : Arb<Int>() {
            override fun edgecase(rs: RandomSource): Int? = null

            override fun sample(rs: RandomSource): Sample<Int> {
                if (rs.seed == 5L) {
                    throw err
                }
                return 1.asSample()
            }
        }
        val result = arb
            .withCheck {}
            .seedMarch(startingSeed = 3, maxIter = 3, printEvery = -1)
        assertNull(result?.value)
        assertSame(err, result?.err)
        assertEquals(5, result?.seed)
    }

    @Test
    fun `seedMarch -- returns assertion failures`() {
        val result = Arb.constant(1)
            .withCheck { assertTrue(it < 0) }
            .seedMarch(maxIter = 1, printEvery = -1)
        assertEquals(1, result?.value)
        assertEquals(0, result?.seed)
        assertTrue(result?.err is AssertionError)
    }

    @Test
    fun `seedMarch -- suppress output`() {
        val out = ByteArrayOutputStream()
        Arb.int(0..100)
            .withCheck {}
            .seedMarch(maxIter = iterations, printEvery = -1, out = PrintStream(out))
        assertTrue(out.toString().isEmpty())
    }

    @Test
    fun `seedMarch -- printEvery`() {
        val out = ByteArrayOutputStream()
        Arb.int(0..50)
            .withCheck {}
            .seedMarch(startingSeed = 3, maxIter = 40, printEvery = 10, out = PrintStream(out))
        assertEquals(
            """
                    |Seed 3...
                    |Seed 13...
                    |Seed 23...
                    |Seed 33...
                    |
            """.trimMargin(),
            out.toString()
        )
    }
}
