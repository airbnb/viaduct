package viaduct.arbitrary.common

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.Sample
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.int
import io.kotest.property.asSample
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CheckedArbTest : KotestPropertyBase() {
    @Test
    fun `checkAll -- succeeds`() {
        assertThatCode {
            Arb.constant(1).withCheck({}).checkAll()
        }.doesNotThrowAnyException()
    }

    @Test
    fun `checkAll -- fails`() {
        val err = RuntimeException()
        assertThatThrownBy {
            Arb.constant(1)
                .withCheck { throw err }
                .checkAll()
        }
            .isInstanceOf(AssertionError::class.java)
            .hasCause(err)
    }

    @Test
    fun `minViolation -- succeeds`() {
        val mv = Arb.constant(1)
            .withCheck {}
            .minViolation(Comparator.naturalOrder(), randomSource)
        assertThat(mv).isNull()
    }

    @Test
    fun `minViolation -- fails`() {
        val err = RuntimeException()
        val mv = Arb.int(0..5)
            .withCheck { i ->
                if (i == 0) throw err
            }
            .minViolation(Comparator.naturalOrder(), randomSource)
        assertThat(mv).isEqualTo(Violation(0, err, seed))
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
        assertThat(mv).isEqualTo(Violation(1, err, seed))
    }

    @Test
    fun `seedMarch -- succeeds`() {
        val result = Arb.int(0..100)
            .withCheck {}
            .seedMarch(maxIter = iterations, printEvery = -1)
        assertThat(result).isNull()
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
        assertThat(result).isEqualTo(Violation(1, err, 3))
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
        assertThat(result?.value).isNull()
        assertThat(result?.err).isSameAs(err)
        assertThat(result?.seed).isEqualTo(5)
    }

    @Test
    fun `seedMarch -- returns assertion failures`() {
        val result = Arb.constant(1)
            .withCheck { assertThat(it).isLessThan(0) }
            .seedMarch(maxIter = 1, printEvery = -1)
        assertThat(result?.value).isEqualTo(1)
        assertThat(result?.seed).isEqualTo(0)
        assertThat(result?.err).isInstanceOf(AssertionError::class.java)
    }

    @Test
    fun `seedMarch -- suppress output`() {
        val out = ByteArrayOutputStream()
        Arb.int(0..100)
            .withCheck {}
            .seedMarch(maxIter = iterations, printEvery = -1, out = PrintStream(out))
        assertThat(out.toString()).isEmpty()
    }

    @Test
    fun `seedMarch -- printEvery`() {
        val out = ByteArrayOutputStream()
        Arb.int(0..50)
            .withCheck {}
            .seedMarch(startingSeed = 3, maxIter = 40, printEvery = 10, out = PrintStream(out))
        assertThat(out.toString()).isEqualToNormalizingNewlines(
            """
                    |Seed 3...
                    |Seed 13...
                    |Seed 23...
                    |Seed 33...
                    |
            """.trimMargin()
        )
    }
}
