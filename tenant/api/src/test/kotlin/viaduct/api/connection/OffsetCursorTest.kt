@file:OptIn(ExperimentalApi::class)

package viaduct.api.connection

import java.util.Base64
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.apiannotations.ExperimentalApi

class OffsetCursorTest {
    @Test
    fun `fromOffset creates cursor with encoded value`() {
        val cursor = OffsetCursor.fromOffset(42)
        // Value should be a Base64 encoded string
        assertTrue(cursor.value.isNotEmpty())
        // Should be URL-safe Base64 (no +, /, =)
        assertFalse(cursor.value.contains("+"))
        assertFalse(cursor.value.contains("/"))
        assertFalse(cursor.value.contains("="))
    }

    @Test
    fun `fromOffset and toOffset roundtrip`() {
        for (offset in listOf(0, 1, 10, 100, 1000, Int.MAX_VALUE - 1)) {
            val cursor = OffsetCursor.fromOffset(offset)
            val decoded = cursor.toOffset()
            Assertions.assertEquals(offset, decoded, "Roundtrip failed for offset $offset")
        }
    }

    @Test
    fun `fromOffset rejects negative offset`() {
        val exception = assertThrows<IllegalArgumentException> {
            OffsetCursor.fromOffset(-1)
        }
        assertTrue(exception.message!!.contains("non-negative"))
        assertTrue(exception.message!!.contains("-1"))
    }

    @Test
    fun `toOffset decodes valid cursor`() {
        val cursor = OffsetCursor.fromOffset(42)
        Assertions.assertEquals(42, cursor.toOffset())
    }

    @Test
    fun `toOffset throws on invalid Base64`() {
        val cursor = OffsetCursor("not-valid-base64!!!")
        assertThrows<IllegalArgumentException> {
            cursor.toOffset()
        }
    }

    @Test
    fun `toOffset throws on wrong prefix`() {
        val wrongPrefix = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("wrong:idx:42".toByteArray())
        val cursor = OffsetCursor(wrongPrefix)
        assertThrows<IllegalArgumentException> {
            cursor.toOffset()
        }
    }

    @Test
    fun `toOffset throws on wrong marker`() {
        val wrongMarker = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("__viaduct:wrong:42".toByteArray())
        val cursor = OffsetCursor(wrongMarker)
        assertThrows<IllegalArgumentException> {
            cursor.toOffset()
        }
    }

    @Test
    fun `toOffset throws on non-numeric offset`() {
        val nonNumeric = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("__viaduct:idx:abc".toByteArray())
        val cursor = OffsetCursor(nonNumeric)
        assertThrows<IllegalArgumentException> {
            cursor.toOffset()
        }
    }

    @Test
    fun `toOffset throws on negative offset in cursor`() {
        val negativeOffset = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("__viaduct:idx:-5".toByteArray())
        val cursor = OffsetCursor(negativeOffset)
        assertThrows<IllegalArgumentException> {
            cursor.toOffset()
        }
    }

    @Test
    fun `isValid returns true for valid cursor`() {
        val cursor = OffsetCursor.fromOffset(100)
        assertTrue(OffsetCursor.isValid(cursor.value))
    }

    @Test
    fun `isValid returns false for invalid cursor`() {
        assertFalse(OffsetCursor.isValid("invalid"))
        assertFalse(OffsetCursor.isValid(""))

        val wrongFormat = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("bad:format".toByteArray())
        assertFalse(OffsetCursor.isValid(wrongFormat))
    }

    @Test
    fun `fromOffset is deterministic - same input same output`() {
        val cursor1 = OffsetCursor.fromOffset(50)
        val cursor2 = OffsetCursor.fromOffset(50)
        assertEquals(cursor1.value, cursor2.value)
    }

    @Test
    fun `handles large offsets`() {
        val largeOffset = Int.MAX_VALUE - 1
        val cursor = OffsetCursor.fromOffset(largeOffset)
        Assertions.assertEquals(largeOffset, cursor.toOffset())
    }

    @Test
    fun `handles zero offset`() {
        val cursor = OffsetCursor.fromOffset(0)
        Assertions.assertEquals(0, cursor.toOffset())

        val decoded = Base64.getUrlDecoder().decode(cursor.value)
        val payload = String(decoded, Charsets.UTF_8)
        assertEquals("__viaduct:idx:0", payload)
    }

    @Test
    fun `different offsets produce different cursor values`() {
        val cursor0 = OffsetCursor.fromOffset(0)
        val cursor1 = OffsetCursor.fromOffset(1)
        val cursor100 = OffsetCursor.fromOffset(100)

        assertTrue(cursor0.value != cursor1.value)
        assertTrue(cursor1.value != cursor100.value)
        assertTrue(cursor0.value != cursor100.value)
    }

    @Test
    fun `cursor value follows __viaduct_idx format when decoded`() {
        val cursor = OffsetCursor.fromOffset(42)
        val decoded = Base64.getUrlDecoder().decode(cursor.value)
        val payload = String(decoded, Charsets.UTF_8)
        assertEquals("__viaduct:idx:42", payload)
    }

    @Test
    fun `value class wraps the encoded string not the offset`() {
        val cursor = OffsetCursor.fromOffset(42)
        // The value property should be the encoded string, not "42"
        assertTrue(cursor.value != "42")
    }
}
