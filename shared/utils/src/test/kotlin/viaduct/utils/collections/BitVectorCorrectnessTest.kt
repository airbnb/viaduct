package viaduct.utils.collections

import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Regression tests for BitVector correctness issues.
 * These tests were written to expose specific bugs in the implementation.
 */
class BitVectorCorrectnessTest : BitVectorSetup() {
    /**
     * Bug #1: equals/hashCode use (size+1)/64 but extraBits allocated with (size-1)/64
     * For size=128: extraBits.size = (128-1)/64 = 1, but equals uses len = (128+1)/64 = 2
     * This causes ArrayIndexOutOfBoundsException
     */
    @Test
    fun `equals - size 128 should not throw ArrayIndexOutOfBoundsException`() {
        val v1 = mk(128)
        val v2 = mk(128)

        // Set some bits to make test more interesting
        v1.set(0).set(63).set(64).set(127)
        v2.set(0).set(63).set(64).set(127)

        // This should work without throwing
        assertEquals(v1, v2)
        assertEquals(v1.hashCode(), v2.hashCode())
    }

    @Test
    fun `equals - size 64 multiples should work correctly`() {
        val sizes = listOf(64, 128, 192, 256)

        for (size in sizes) {
            val v1 = mk(size)
            val v2 = mk(size)

            // Empty vectors should be equal
            assertEquals(v1, v2, "Empty vectors of size $size should be equal")
            assertEquals(v1.hashCode(), v2.hashCode(), "Hash codes should match for size $size")

            // Set all bits and test again
            for (i in 0 until size) {
                v1.set(i)
                v2.set(i)
            }
            assertEquals(v1, v2, "Full vectors of size $size should be equal")
            assertEquals(v1.hashCode(), v2.hashCode(), "Hash codes should match for full size $size")
        }
    }

    @Test
    fun `equals - comprehensive boundary sizes with random patterns`() {
        val sizes = listOf(0, 1, 63, 64, 65, 127, 128, 129)
        val rng = Random(42)

        for (size in sizes) {
            if (size == 0) continue

            val v1 = mk(size)
            val v2 = mk(size)

            // Set random bits
            for (i in 0 until size) {
                if (rng.nextBoolean()) {
                    v1.set(i)
                    v2.set(i)
                }
            }

            assertEquals(v1, v2, "Vectors with random pattern at size $size should be equal")
            assertEquals(v1.hashCode(), v2.hashCode(), "Hash codes should match for size $size")

            // Flip one bit in v2 and verify they're not equal
            if (size > 0) {
                // If bit 0 is set in both, clear it in v2; otherwise set it in v2
                if (v1.get(0)) {
                    v2.clear(0)
                } else {
                    v2.set(0)
                }
                assertNotEquals(v1, v2, "Vectors should not be equal after bit flip at size $size")
            }
        }
    }

    /**
     * Bug #2: get(idx, count) has issues with bit extraction spanning words
     * The test shows incorrect results when extracting bits that span word boundaries
     */
    @Test
    fun `get(idx, count) - spanning full 64 bits in high word`() {
        // Create a vector with all bits set
        val v = mk(-128)

        // Test idx=0, count=64: should get all bits from first word
        assertEquals(-1L, v.get(0, 64), "get(0, 64) should return -1L")

        // Test idx=64, count=64: should get all bits from second word
        assertEquals(-1L, v.get(64, 64), "get(64, 64) should return -1L")

        // Test idx=1, count=64: spans from bit 1 to bit 64
        // This tests the case where countInHi approaches 64
        // countInLo = 64 - 1 = 63, countInHi = 64 - 63 = 1
        val result1 = v.get(1, 64)
        assertEquals(-1L, result1, "get(1, 64) on all-ones vector should return -1L")

        // Test idx=32, count=64: spans more evenly
        // countInLo = 32, countInHi = 32
        val result32 = v.get(32, 64)
        assertEquals(-1L, result32, "get(32, 64) on all-ones vector should return -1L")

        // Test idx=63, count=64: minimal bits from low word
        // countInLo = 1, countInHi = 63
        val result63 = v.get(63, 64)
        assertEquals(-1L, result63, "get(63, 64) on all-ones vector should return -1L")
    }

    @Test
    fun `get(idx, count) - various boundary cases`() {
        val v = mk(192)
        // Set all bits in second and third 64-bit words
        for (i in 64 until 192) {
            v.set(i)
        }

        // Test various spanning scenarios
        assertEquals(0L, v.get(0, 64), "First 64 bits should be clear")
        assertEquals(-1L, v.get(64, 64), "Second 64 bits should be all set")
        assertEquals(-1L, v.get(128, 64), "Third 64 bits should be all set")

        // Test spanning from first into second word
        val span1 = v.get(32, 64) // Spans bits 32-95
        val expected1 = (-1L shl 32) // Lower 32 bits clear, upper 32 bits set
        assertEquals(expected1, span1, "get(32, 64) should have lower 32 clear, upper 32 set")

        // Test spanning from second into third word
        val span2 = v.get(96, 64) // Spans bits 96-159
        assertEquals(-1L, span2, "get(96, 64) should be all set")
    }

    @Test
    fun `hashCode - consistent across equal vectors`() {
        val sizes = listOf(0, 1, 63, 64, 65, 127, 128, 129, 192, 256)

        for (size in sizes) {
            val v1 = mk(size)
            val v2 = mk(size)
            val v3 = mk(size)

            // All empty
            assertEquals(v1.hashCode(), v2.hashCode(), "Empty vectors size $size should have same hash")
            assertEquals(v1, v2)

            // Set the same pattern
            if (size > 0) {
                v1.set(0)
                v2.set(0)
                v3.set(0)
                if (size > 1) {
                    v1.set(size - 1)
                    v2.set(size - 1)
                    v3.set(size - 1)
                }

                assertEquals(v1.hashCode(), v2.hashCode(), "Equal vectors size $size should have same hash")
                assertEquals(v1, v2)
                assertEquals(v1.hashCode(), v3.hashCode())
                assertEquals(v2.hashCode(), v3.hashCode())
            }
        }
    }
}
