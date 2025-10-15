package viaduct.utils.collections

import java.util.Optional
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.fail

class OptionTest {
    @Test
    fun `None -- getOrNull`() {
        assertNull(None.getOrNull())
    }

    @Test
    fun `None -- get`() {
        assertThrows<NoSuchElementException> {
            None.get()
        }

        // sanity check that exception type is the same as what's thrown by the equivalent java.util.Optional
        assertThrows<NoSuchElementException> {
            Optional.empty<Unit>().get()
        }
    }

    @Test
    fun `None -- isEmpty`() {
        assertTrue(None.isEmpty())
    }

    @Test
    fun `None -- map`() {
        assertSame(None, None.map { 1 })
    }

    @Test
    fun `None -- flatMap`() {
        assertSame(None, None.flatMap { Some(1) })
    }

    @Test
    fun `None -- toList`() {
        assertTrue(None.toList().isEmpty())
    }

    @Test
    fun `None -- forEach`() {
        None.forEach { fail("forEach should not be invoked") }
    }

    @Test
    fun `None -- contains`() {
        assertFalse(0 in None)
        assertFalse(null in None)
    }

    @Test
    fun `Some -- getOrNull`() {
        assertEquals(1, Some(1).getOrNull())
        assertNull(Some(null).getOrNull())
    }

    @Test
    fun `Some -- get`() {
        assertEquals(1, Some(1).get())
        assertNull(Some(null).get())
    }

    @Test
    fun `Some -- isEmpty`() {
        assertFalse(Some(1).isEmpty())
        assertFalse(Some(None).isEmpty())
        assertFalse(Some(null).isEmpty())
    }

    @Test
    fun `Some -- map`() {
        assertEquals(Some(2), Some(1).map { it + 1 })
    }

    @Test
    fun `Some -- flatMap`() {
        assertEquals(Some(2), Some(1).flatMap { Some(it + 1) })
        assertEquals(None, Some(1).flatMap { None })
    }

    @Test
    fun `Some -- toList`() {
        assertEquals(listOf(1), Some(1).toList())
    }

    @Test
    fun `Some -- forEach`() {
        var sawValue: Int? = null
        Some(1).forEach { sawValue = it }
        assertEquals(1, sawValue)
    }

    @Test
    fun `Some -- contains`() {
        assertTrue(1 in Some(1))
        assertFalse(0 in Some(1))

        // type variance
        assertTrue(1 in Some<Number>(1))
        assertFalse(1L in Some<Number>(1))
        assertFalse(0 in Some<Number>(1))
        assertTrue(null in Some(null))
    }

    @Test
    fun `Companion -- invoke`() {
        assertEquals(None, Option(null))
        assertEquals(Some(1), Option(1))
    }
}
