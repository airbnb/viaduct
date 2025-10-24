package viaduct.utils.collections

import com.google.common.testing.EqualsTester
import java.lang.Math
import java.lang.StringBuilder
import java.util.Random
import kotlin.math.min
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.utils.collections.BitVector.Builder

internal class BitVectorBasicTests : BitVectorSetup() {
    @Test
    fun sizeIsAsSet() {
        assertEquals(0, mk(0).size)
        assertEquals(1, mk(1).size)
        assertEquals(WS - 1, mk(WS - 1).size)
        assertEquals(WS, mk(WS).size)
        assertEquals(2 * WS - 1, mk(2 * WS - 1).size)
        assertEquals(2 * WS, mk(2 * WS).size)
        assertEquals(1010001000, mk(1010001000).size)

        assertEquals(1, mk(-1).size)
        assertEquals(WS - 1, mk(-WS + 1).size)
        assertEquals(WS, mk(-WS).size)
        assertEquals(2 * WS - 1, mk(-2 * WS + 1).size)
        assertEquals(2 * WS, mk(-2 * WS).size)
        assertEquals(1010001000, mk(-1010001000).size)
    }

    @Test
    fun initialValueIsCorrect() {
        assertEquals(-1, isEmpty(mk(1)))
        assertEquals(-1, isEmpty(mk(WS - 1)))
        assertEquals(-1, isEmpty(mk(WS)))
        assertEquals(-1, isEmpty(mk(2 * WS - 1)))
        assertEquals(-1, isEmpty(mk(2 * WS)))
        assertEquals(-1, isEmpty(mk(2 * WS + 1)))
        assertEquals(-1, isEmpty(mk(1000000)))

        assertEquals(-1, isFull(mk(-1)))
        assertEquals(-1, isFull(mk(-WS + 1)))
        assertEquals(-1, isFull(mk(-WS)))
        assertEquals(-1, isFull(mk(-WS - 1)))
        assertEquals(-1, isFull(mk(-2 * WS + 1)))
        assertEquals(-1, isFull(mk(-2 * WS)))
        assertEquals(-1, isFull(mk(-2 * WS - 1)))
        assertEquals(-1, isFull(mk(-1000000)))
    }

    @Test
    fun checkInvariants() {
        mk(0).checkInvariants()
        mk(1).checkInvariants()
        mk(-1).checkInvariants()
        mk(2).checkInvariants()
        mk(-2).checkInvariants()

        mk(WS - 1).checkInvariants()
        mk(WS).checkInvariants()
        mk(WS + 1).checkInvariants()

        mk(-WS + 1).checkInvariants()
        mk(-WS).checkInvariants()
        mk(-WS - 1).checkInvariants()

        mk(2 * WS - 1).checkInvariants()
        mk(2 * WS).checkInvariants()
        mk(2 * WS + 1).checkInvariants()

        mk(-2 * WS + 1).checkInvariants()
        mk(-2 * WS).checkInvariants()
        mk(-2 * WS - 1).checkInvariants()
    }

    @Test
    fun getOnNew() {
        var size = 1
        for (i in 0..14) {
            val v = mk(size)
            v.checkInvariants()
            for (j in 0 until size) {
                assertFalse(v.get(j), msg(i, j))
            }
            size = (1.5 * size + i).toInt()
        }
    }

    @Test
    fun `get nothing from empty vector`() {
        assertEquals(0L, mk(0).get(0, 0))
    }

    @Test
    fun getRangeOnNew() {
        var size = 1
        for (i in 0..14) {
            val v = mk(size)
            for (j in 0 until min(WS.toDouble(), size.toDouble()).toInt()) assertEquals(0L, v.get(0, j), msg(i, j))
            size = (1.5 * size + i).toInt()
        }
    }

    @Test
    fun setAndGet() {
        var size = 1
        for (i in 0..14) {
            val v: BitVector = mk(size)
            for (j in 0 until size) {
                assertTrue(v.set(j).get(j), msg(i, j))
                var k = 1
                while (k + j < size) {
                    assertFalse(v.get(j + k), msg(i, j, k))
                    k++
                }
                assertTrue(v.get(j))
            }
            v.checkInvariants()
            size = (1.5 * size + i).toInt()
        }
    }

    @Test
    fun invert() {
        var va: BitVector = mk(0).invert()
        assertEquals(0, va.size)

        va = mk(1).invert()
        va.checkInvariants()
        assertTrue(va.get(0))

        va = mk(2).invert()
        va.checkInvariants()
        assertEquals(3L, va.get(0, 2))
        va = mk(WS).invert()
        assertEquals(-1L, va.get(0, WS))

        va = mk(WS + 1).invert()
        va.checkInvariants()
        assertTrue(va.get(WS))
        assertEquals(-1L, va.get(1, WS))

        va = mk(10 * WS).invert()
        va.checkInvariants()
        for (i in 0..9) assertEquals(-1L, va.get(i * WS, WS), "Iteration $i")
    }

    @Test
    fun clear() {
        var va: BitVector = mk(1).invert()
        assertFalse(va.clear(0).get(0))
        va.checkInvariants()

        va = mk(2).invert()
        assertFalse(va.clear(0).get(0))
        assertEquals(2L, va.get(0, 2))
        assertFalse(va.clear(1).get(1))
        assertEquals(0L, va.get(0, 2))
        va.checkInvariants()

        va = mk(WS).invert()
        assertFalse(va.clear(WS - 2).get(WS - 2))
        assertEquals((1L shl (WS - 2)).inv(), va.get(0, WS))
        va.checkInvariants()

        va = mk(WS + 1).invert()
        assertFalse(va.clear(WS - 1).get(WS - 1))
        assertFalse(va.clear(WS).get(WS))
        assertEquals(4611686018427387902L, va.clear(1).get(1, WS))
        assertEquals(9223372036854775805L, va.get(0, WS))
        va.checkInvariants()

        va = mk(2 * WS + 1).invert()
        assertFalse(va.clear(2 * WS - 1).get(2 * WS - 1))
        assertFalse(va.clear(2 * WS).get(2 * WS))
        va.checkInvariants()
    }

    @Test
    fun lsr() {
        var va: BitVector = mk(0).lsr()
        assertEquals(0, va.size)

        va = mk(-1).lsr()
        va.checkInvariants()
        assertEquals(0, va.size)

        va = mk(-2).lsr()
        va.checkInvariants()
        assertEquals(1, va.size)
        assertEquals(1L, va.get(0, 1))

        va = mk(-WS).lsr()
        va.checkInvariants()
        assertEquals(WS - 1, va.size)
        assertEquals(9223372036854775807L, va.get(0, WS - 1))

        va = mk(-WS - 1).lsr()
        va.checkInvariants()
        assertEquals(WS, va.size)
        assertEquals(-1L, va.get(0, WS))

        va = mk(-WS - 2).lsr()
        va.checkInvariants()
        assertEquals(WS + 1, va.size)
        assertEquals(-1L, va.get(0, WS))
        assertEquals(1L, va.get(WS, 1))

        va = mk(-2 * WS).lsr()
        va.checkInvariants()
        assertEquals(2 * WS - 1, va.size)
        assertEquals(-1L, va.get(0, WS))
        assertEquals(9223372036854775807L, va.get(WS, WS - 1))

        va = mk(-2 * WS - 1).lsr()
        va.checkInvariants()
        assertEquals(2 * WS, va.size)
        assertEquals(-1L, va.get(0, WS))
        assertEquals(-1L, va.get(WS, WS))

        va = mk(-2 * WS - 2).lsr()
        va.checkInvariants()
        assertEquals(2 * WS + 1, va.size)
        assertEquals(-1L, va.get(0, WS))
        assertEquals(-1L, va.get(WS, WS))
        assertEquals(1L, va.get(2 * WS, 1))
    }

    @Test
    fun indices() {
        var iter: Iterator<Int?> = mk(0).indices().iterator()
        assertFalse(iter.hasNext())

        val i1: Iterator<Int> = mk(0).indices().iterator()
        assertThrows(
            NoSuchElementException::class.java
        ) { i1.next() }
        assertFalse(i1.hasNext())

        iter = mk(1).indices().iterator()
        assertTrue(iter.hasNext())
        assertEquals(0, iter.next())
        assertFalse(iter.hasNext())

        val i2: Iterator<Int> = mk(2).indices().iterator()
        assertTrue(i2.hasNext())
        assertEquals(0, i2.next())
        assertTrue(i2.hasNext())
        assertEquals(1, i2.next())
        assertFalse(i2.hasNext())
        assertThrows(
            NoSuchElementException::class.java,
        ) { i2.next() }
    }

    @Test
    fun getRange() {
        val va = mk(2).set(0).set(1)
        va.checkInvariants()
        assertEquals(0L, va.get(0, 0))
        assertEquals(0L, va.get(1, 0))
        assertEquals(1L, va.get(0, 1))
        assertEquals(1L, va.get(1, 1))
        assertEquals(3L, va.get(0, 2))

        val vb = mk(WS).invert()
        vb.checkInvariants()
        assertEquals(0L, vb.get(WS - 1, 0))
        assertEquals(1L, vb.get(0, 1))
        assertEquals(3L, vb.get(0, 2))
        assertEquals(-1L, vb.get(0, WS))
        assertEquals(1L, vb.get(WS - 1, 1))

        val vc = mk(2 * WS).invert()
        vc.checkInvariants()
        assertEquals(0L, vc.get(WS, 0))
        assertEquals(1L, vc.get(WS - 1, 1))
        assertEquals(1L, vc.get(WS, 1))
        assertEquals(3L, vc.get(WS - 1, 2))
        assertEquals(-1L, vc.get(1, WS))
        assertEquals(-1L, vc.get(WS - WS / 2, WS))
        assertEquals(-1L, vc.get(WS - 1, WS))

        for (i in 2..3) {
            val v = mk(WS * i - 1)
            for (j in 0..3) v.set((WS * (i - 1) - 2) + j) // Span word boundary

            v.checkInvariants()
            assertEquals(60L, v.get(WS * (i - 1) - 4, 6), msg(i))
        }
    }

    @Test
    fun build() {
        assertEquals(0, mkb().build().size)
        assertEquals(0, mkb().add(1L, 0).build().size)
        assertEquals(1, mkb().add(1L, 1).build().size)
        assertEquals(6L, mkb().add(0L, 1).add(3L, 2).build().get(0, 3))

        var v: BitVector = mkb().add(0L, WS - 1).add(1L, 1).build()
        v.checkInvariants()
        assertEquals(WS, v.size)
        assertEquals(1L shl (WS - 1), v.get(0, WS))

        v = mkb().add(0L, WS - 1).add(15L, 4).build()
        v.checkInvariants()
        assertEquals(WS + 3, v.size)
        assertEquals(15L, v.get(WS - 1, 4))

        v = mkb().add(1L, WS - 1).add(1L, 1).build()
        v.checkInvariants()
        assertEquals(WS, v.size)
        assertEquals(1L or (1L shl (WS - 1)), v.get(0, WS))

        v = mkb().add(0L, WS).add(1L, 1).build()
        v.checkInvariants()
        assertEquals(WS + 1, v.size)
        assertEquals(2L, v.get(WS - 1, 2))

        v = mkb().add(0L, WS - 1).add(3L, 2).build()
        v.checkInvariants()
        assertEquals(WS + 1, v.size)
        assertEquals(3L, v.get(WS - 1, 2))

        v = mkb().add(0L, WS).add(0L, WS - 2).add(121L, 4).build()
        v.checkInvariants()
        assertEquals(2 * WS + 2, v.size)
        assertEquals(9L, v.get(2 * WS - 2, 4))
    }

    @Test
    fun buildWithJunk() {
        assertEquals(1L, mkb().add(3L, 1).add(0L, 2).build().get(0, 2))
        assertEquals(3L, mkb().add(0L, WS - 1).add(15L, 2).add(0L, 2).build().get(WS - 1, 4))
    }

    @Test
    fun `builder with exact multiple of 64 bits preserves data correctly`() {
        // Bug: When bitsInBuffer == 0, Builder.build() creates an oversized extraBits array
        // For 128 bits: creates extraBits.size=2 instead of 1, violating the invariant:
        // ((size + 63) >> 6) - 1 == extraBits.size

        val v = mkb()
            .add(-1L, 64) // Add 64 bits
            .add(-1L, 64) // Add another 64 bits (total 128, bitsInBuffer=0)
            .build()

        // checkInvariants() validates: ((128 + 63) >> 6) - 1 == extraBits.size
        // Expected: 1, Actual: 2 (bug)
        v.checkInvariants()
        assertEquals(128, v.size)
        assertEquals(-1L, v.get(0, 64))
        assertEquals(-1L, v.get(64, 64))
    }

    // Equality testing
    private val EQUALS_CASES = arrayOf<Array<Any>>(
        // Zero-length
        arrayOf<Any>(mk(0), mkb().build()), // Length one, clear
        arrayOf<Any>(mk(1), mkb().add(0L, 1).build()), // Length one, set
        arrayOf<Any>(mk(1).set(0), mkb().add(1L, 1).build()), // Length two, 0b00
        arrayOf<Any>(mk(2), mkb().add(0L, 2).build(), mk(2).copy()), // Length two, 0b01
        arrayOf<Any>(mk(2).set(0), mkb().add(1L, 2).build(), mk(2).set(0).copy()), // Length two, 0b10
        arrayOf<Any>(mk(2).set(1), mkb().add(10L, 2).build()), // Length two, 0b11
        arrayOf<Any>(mk(2).set(0).set(1), mkb().add(11L, 2).build(), mkb().add(1L, 1).add(1L, 1).build()), // Length WS, 0b10...0
        arrayOf<Any>(mk(WS).set(WS - 1), mkb().add(0L, 63).add(1L, 1).build()), // Length WS+1, 0b11...0
        arrayOf<Any>(
            mk(WS + 1).set(WS - 1).set(WS),
            mkb().add(0L, 63).add(1L, 1).add(1L, 1).build(),
            mkb().add(0L, 63).add(3L, 2).build(),
            mk(WS + 1).set(WS - 1).set(WS).copy()
        ), // Length 2*WS+2, 0b11...11...0
        arrayOf<Any>(
            mk(2 * WS + 1).set(WS - 1).set(WS).set(2 * WS - 1).set(2 * WS),
            mkb().add(0L, WS - 1).add(3L, 2).add(0L, WS - 2).add(3L, 2).build(),
            mkb().add(1L shl (WS - 1), WS).add(1L, 1).add(0L, WS - 2).add(3L, 2).build(),
            mkb().add(1L shl (WS - 1), WS).add(1L, 1).add(0L, WS - 2).add(3L, 2).build().copy()
        ), // Length 2*WS+2, inversion of 0b11...11...0
        arrayOf<Any>(
            mk(2 * WS + 1).set(WS - 1).set(WS).set(2 * WS - 1).set(2 * WS).invert(),
            mkb().add(0L, WS - 1).add(3L, 2).add(0L, WS - 2).add(3L, 2).build().invert(),
            mkb().add(1L shl (WS - 1), WS).add(1L, 1).add(0L, WS - 2).add(3L, 2).build().invert()
        ),
    )

    @Test
    fun equalsTesting() {
        for (i in EQUALS_CASES.indices) for (j in EQUALS_CASES[i].indices) (EQUALS_CASES[i][j] as BitVector).checkInvariants()

        val tester = EqualsTester()
        for (group in EQUALS_CASES) tester.addEqualityGroup(*group)
        tester.testEquals()
    }

    /** Tests toString and copy - and also retests other methods randomly.  */
    @Test
    fun randomTesting() {
        assertEquals("", mk(0).toString())
        assertEquals("1", mk(1).set(0).toString())
        assertEquals("110", mkb().add(6L, 3).build().toString())

        // Random but deterministic
        val rng = Random(48719302348791230L)
        for (i in 1..14) {
            for (j in 0..2) {
                val sb = StringBuilder()
                val isb = StringBuilder()
                val b: Builder = mkb()
                for (k in 0 until i) {
                    var bits: Long = rng.nextLong() // Intentionally leave all bits to make sure they're ignored
                    b.add(bits, TO_STRING_BITSTOTAKE)
                    for (n in 0 until TO_STRING_BITSTOTAKE) {
                        sb.append(if (0L == (bits and 1L)) '0' else '1')
                        isb.append(if (1L == (bits and 1L)) '0' else '1')
                        bits = bits shr 1
                    }
                }
                val m = msg(i, j)
                val v: BitVector = b.build()
                v.checkInvariants()

                // toString test (also retests Builder)
                val expected: String = sb.reverse().toString()
                assertEquals(expected, v.toString(), m)

                // copy test (also retests equals)
                val cp: BitVector = v.copy()
                cp.checkInvariants()
                assertEquals(v, cp, m)

                // Manually invert: tests set, clear, get(int), and get(int,int)
                val sz: Int = v.size
                var n = 0
                while (n < sz) {
                    val min: Int = Math.min(WS, (sz - n))
                    val rand: Int = rng.nextInt(2 * min)
                    val count = (if ((sz - n) < 4) (sz - n) else rand / 2)
                    if (rand < min) { // Half the time test get(int,int)
                        var bits: Long = v.get(n, count)
                        for (idx in n until n + count) {
                            if (1L == (bits and 1L)) {
                                v.clear(idx)
                            } else {
                                v.set(idx)
                            }
                            bits = bits shr 1
                        }
                    } else {
                        for (idx in n until n + count) if (v.get(idx)) {
                            v.clear(idx)
                        } else {
                            v.set(idx)
                        }
                    }
                    n += count
                }

                assertEquals(expected, cp.toString(), m) // Make sure cp didn't change!

                // Test invert() - and also that the manual-inversion worked
                val iexpected: String = isb.reverse().toString()
                cp.invert()
                assertEquals(iexpected, cp.toString(), m)
                assertEquals(cp, v, m)
                v.checkInvariants()
                cp.checkInvariants()
            }
        }
    }

    companion object {
        private fun isFull(v: BitVector): Int {
            for (i in 0 until v.size) if (!v.get(i)) return i
            return -1
        }

        private fun isEmpty(v: BitVector): Int {
            for (i in 0 until v.size) if (v.get(i)) return i
            return -1
        }

        private fun msg(i: Int): String {
            return "i=$i"
        }

        private fun msg(
            i: Int,
            j: Int
        ): String {
            return "i=$i, j=$j"
        }

        private fun msg(
            i: Int,
            j: Int,
            k: Int
        ): String {
            return "i=$i, j=$j, k=$k"
        }

        private const val TO_STRING_BITSTOTAKE = 23
    }
}
