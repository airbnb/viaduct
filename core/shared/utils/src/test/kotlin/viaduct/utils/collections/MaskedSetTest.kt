package viaduct.utils.collections

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MaskedSetTest {
    private fun <T> mk(vararg values: T): MaskedSet<T> = MaskedSet(values.toList())

    @Test
    fun `ctor -- all indices are valid`() {
        fun check(ms: MaskedSet<Int>) {
            assertEquals(ms.valueToIndex.size, ms.exclude.size)
            ms.valueToIndex.forEach { (_, i) ->
                assertDoesNotThrow {
                    ms.exclude.get(i)
                }
            }
        }

        check(MaskedSet.empty())
        check(mk())
        check(mk(1))
        check(mk(1, 1))
        check(mk(1, 1, 2))
        check(mk(1, 1, 2, 1))
    }

    @Test
    fun empty() {
        assertSame(MaskedSet.empty, MaskedSet.empty.intersect(MaskedSet.empty))
        assertSame(MaskedSet.empty, MaskedSet.empty<Int>())
        assertSame(MaskedSet.empty<Int>(), MaskedSet.empty<Int>())
        assertSame(MaskedSet.empty<String>(), MaskedSet.empty<Int>())

        assertSame(
            MaskedSet.empty,
            mk(1).intersect(MaskedSet.empty())
        )

        assertSame(
            MaskedSet.empty,
            mk(1).intersect(mk(2))
        )
    }

    @Test
    fun `isEmpty -- empty`() {
        assertTrue(mk<Nothing>().isEmpty())
        assertTrue(MaskedSet.empty.isEmpty())
        assertTrue(MaskedSet.empty<Int>().isEmpty())
    }

    @Test
    fun `isEmpty -- non-empty`() {
        assertFalse(mk(1).isEmpty())
        assertFalse(mk(1).intersect(mk(1)).isEmpty())
        assertFalse(mk(1, 2).intersect(mk(2)).isEmpty())
    }

    @Test
    fun `isEmpty -- intersected`() {
        assertTrue(mk(1).intersect(mk()).isEmpty())
        assertTrue(mk(1).intersect(mk(2)).isEmpty())
        assertTrue(mk(1, 2).intersect(mk(3, 4)).isEmpty())
    }

    @Test
    fun `size -- empty`() {
        assertEquals(0, mk<Nothing>().size)
        assertEquals(0, MaskedSet.empty.size)
        assertEquals(0, MaskedSet.empty<Int>().size)
        assertEquals(0, mk(1).intersect(mk(0)).size)
    }

    @Test
    fun `size -- ignores duplicates`() {
        val list = (1..100).toList()
        assertEquals(list.size, MaskedSet(list + list).size)
    }

    @Test
    fun `toList -- empty`() {
        assertEquals(emptyList<Nothing>(), MaskedSet.empty.toList())
        assertEquals(emptyList<Int>(), MaskedSet.empty<Int>().toList())
    }

    @Test
    fun `toList -- non-empty`() {
        assertEquals(listOf(1), mk(1).toList())
        assertEquals(listOf(1), mk(1, 2).intersect(mk(1)).toList())
        assertEquals(listOf(1, 2, 3), mk(1, 2, 3).intersect(mk(3, 2, 1)).toList())
    }

    @Test
    fun `toList -- intersected with smaller MaskedSet`() {
        assertEquals(listOf(2, 3), mk(1, 2, 3).intersect(mk(3, 2)).toList())
    }

    @Test
    fun `toList -- intersected with larger MaskedSet`() {
        assertEquals(listOf(1, 2, 3), mk(1, 2, 3).intersect(mk(4, 3, 2, 1)).toList())
    }

    @Test
    fun `intersect -- empty`() {
        assertSame(MaskedSet.empty, MaskedSet.empty.intersect(MaskedSet.empty))
        assertSame(MaskedSet.empty, mk(1).intersect(mk(2)))
    }

    @Test
    fun `intersect -- self`() {
        // same reference
        val ms = mk(1)
        assertSame(ms, ms.intersect(ms))
    }

    @Test
    fun `intersect -- structurally equal`() {
        assertEquals(mk(1), mk(1).intersect(mk(1)))
    }

    @Test
    fun `intersect -- structural equal -- diff ordering`() {
        assertEquals(
            mk(1, 2),
            mk(1, 2, 3, 4).intersect(mk(2, 1, 10))
        )
    }

    @Test
    fun `equals -- empty`() {
        assertEquals(mk<Nothing>(), mk<Nothing>())
    }

    @Test
    fun `equals -- empty -- diff types`() {
        assertEquals(mk<Nothing>(), mk<Int>())
    }

    @Test
    fun `equals -- self`() {
        val ms = mk(1)
        assertEquals(ms, ms)
    }

    @Test
    fun `equals -- structurally equal`() {
        assertEquals(mk(1), mk(1))
    }

    @Test
    fun `equals -- intersected`() {
        // original sets are not equal
        val left = mk(1, 2)
        val right = mk(1, 3)

        assertNotEquals(left, right)

        // ...but are equal when masked
        assertEquals(
            left.intersect(mk(1, 3)),
            right.intersect(mk(1, 2)),
        )
    }

    @Test
    fun `iterator -- empty`() {
        val iter = MaskedSet.empty<Int>().iterator()
        assertFalse(iter.hasNext())
    }

    @Test
    fun `iterator -- non-empty preserves order`() {
        val ms = mk(1, 2, 3)
        val result = mutableListOf<Int>()
        for (value in ms) {
            result.add(value)
        }
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `iterator -- intersected skips excluded elements`() {
        val ms = mk(1, 2, 3, 4, 5).intersect(mk(2, 4))
        val result = mutableListOf<Int>()
        for (value in ms) {
            result.add(value)
        }
        assertEquals(listOf(2, 4), result)
    }

    @Test
    fun `iterator -- throws NoSuchElementException when exhausted`() {
        val iter = mk(1).iterator()
        assertTrue(iter.hasNext())
        assertEquals(1, iter.next())
        assertFalse(iter.hasNext())

        assertThrows<NoSuchElementException> {
            iter.next()
        }
    }

    @Test
    fun `iterator -- matches toList result`() {
        val testCases = listOf(
            mk<Int>(),
            mk(1),
            mk(1, 2, 3),
            mk(1, 2, 3).intersect(mk(2)),
            mk(1, 2, 3, 4, 5).intersect(mk(5, 3, 1))
        )

        testCases.forEach { ms ->
            val fromIterator = mutableListOf<Int>()
            ms.forEach { fromIterator.add(it) }
            assertEquals(ms.toList(), fromIterator)
        }
    }
}
