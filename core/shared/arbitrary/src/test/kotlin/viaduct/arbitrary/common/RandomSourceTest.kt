package viaduct.arbitrary.common

import io.kotest.property.RandomSource
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RandomSourceTest {
    @Test
    fun `sampleWeight endpoints consume no randomness`() {
        val source = RandomSource.seeded(42L)
        repeat(20) {
            assertFalse(source.sampleWeight(0.0))
            assertTrue(source.sampleWeight(1.0))
        }
        assertEquals(RandomSource.seeded(42L).random.nextLong(), source.random.nextLong())
    }

    @Test
    fun `sampleWeight includes the boundary`() {
        val source = RandomSource(
            object : Random() {
                override fun nextBits(bitCount: Int): Int = error("Unexpected random draw")

                override fun nextDouble(
                    from: Double,
                    until: Double
                ): Double = 0.5
            },
            0L
        )
        assertTrue(source.sampleWeight(0.5))
        assertFalse(source.sampleWeight(0.4))
    }

    @Test
    fun `fork produces a reproducible independent child and advances its parent once`() {
        val parent = RandomSource.seeded(42L)
        val replayParent = RandomSource.seeded(42L)
        val expectedParent = RandomSource.seeded(42L)
        val child = parent.fork()
        val replayChild = replayParent.fork()
        assertNotSame(parent, child)
        assertEquals(expectedParent.random.nextLong(), child.seed)
        repeat(20) {
            assertEquals(replayChild.random.nextLong(), child.random.nextLong())
        }
        repeat(20) { child.random.nextLong() }
        val next = parent.random.nextLong()
        assertEquals(replayParent.random.nextLong(), next)
        assertEquals(expectedParent.random.nextLong(), next)
    }
}
