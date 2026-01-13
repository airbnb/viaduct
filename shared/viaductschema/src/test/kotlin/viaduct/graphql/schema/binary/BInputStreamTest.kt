package viaduct.graphql.schema.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for BInputStream buffer management and edge cases.
 *
 * These tests verify that BInputStream correctly handles strings that cross
 * buffer boundaries and require buffer refills during reading.
 */
class BInputStreamTest {
    @Test
    fun `readIdentifier handles strings at buffer boundary`() {
        // Create a string that will cross the buffer refill boundary
        val longId = "x".repeat(100)
        val baos = ByteArrayOutputStream()
        baos.write(longId.toByteArray(StandardCharsets.US_ASCII))
        baos.write(0) // Null terminator

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        val result = stream.readIdentifier()
        assertEquals(longId, result)
    }

    @Test
    fun `readUTF8String handles strings at buffer boundary`() {
        val longString = "x".repeat(100)
        val baos = ByteArrayOutputStream()
        baos.write(longString.toByteArray(StandardCharsets.UTF_8))
        baos.write(0) // Null terminator

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        val result = stream.readUTF8String()
        assertEquals(longString, result)
    }

    @Test
    fun `readIdentifier handles very short strings`() {
        val shortId = "x"
        val baos = ByteArrayOutputStream()
        baos.write(shortId.toByteArray(StandardCharsets.US_ASCII))
        baos.write(0) // Null terminator

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        val result = stream.readIdentifier()
        assertEquals(shortId, result)
    }

    @Test
    fun `readUTF8String handles empty strings`() {
        val baos = ByteArrayOutputStream()
        baos.write(0) // Just null terminator

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        val result = stream.readUTF8String()
        assertEquals("", result)
    }

    @Test
    fun `readUTF8String handles UTF-8 multibyte characters`() {
        val utf8String = "hello\u00E9\u4E2D\u6587" // Contains accented char and Chinese chars
        val baos = ByteArrayOutputStream()
        baos.write(utf8String.toByteArray(StandardCharsets.UTF_8))
        baos.write(0) // Null terminator

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        val result = stream.readUTF8String()
        assertEquals(utf8String, result)
    }

    @Test
    fun `skipPadding aligns to 4-byte boundary`() {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(1, 2, 3)) // 3 bytes, needs 1 padding byte
        baos.write(0) // Padding byte
        baos.write(byteArrayOf(5, 6, 7, 8)) // Next aligned data

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        stream.read()
        stream.read()
        stream.read()
        stream.skipPadding() // Should skip 1 byte to align

        val nextByte = stream.read()
        assertEquals(5, nextByte)
    }

    @Test
    fun `skipPadding does nothing when already aligned`() {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(1, 2, 3, 4)) // Already 4-byte aligned
        baos.write(byteArrayOf(5, 6, 7, 8)) // Next data

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        stream.read()
        stream.read()
        stream.read()
        stream.read()
        stream.skipPadding() // Should skip 0 bytes

        val nextByte = stream.read()
        assertEquals(5, nextByte)
    }

    @Test
    fun `readInt reads little-endian integer correctly`() {
        val baos = ByteArrayOutputStream()
        // Write 0x01020304 in little-endian: 04 03 02 01
        baos.write(byteArrayOf(0x04, 0x03, 0x02, 0x01))

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        val result = stream.readInt()
        assertEquals(0x01020304, result)
    }

    @Test
    fun `readLong reads little-endian long correctly`() {
        val baos = ByteArrayOutputStream()
        // Write 0x0102030405060708 in little-endian: 08 07 06 05 04 03 02 01
        baos.write(byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01))

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, 200)

        val result = stream.readLong()
        assertEquals(0x0102030405060708L, result)
    }

    /**
     * Test for off-by-one bug in readIdentifier.
     *
     * The fast path checks for 11 bytes (`if (lim < (pos + 11)) refill()`)
     * then loops while `len < 10`. When the loop exits with len=10 (no terminator
     * found in first 10 chars), it checks `buf[pos + 10]` which is the 11th byte.
     *
     * To trigger: Use a small buffer (16 bytes), read a 4-char string first,
     * then try to read a 10-char string. After the first read, pos=5 (4 chars + terminator).
     * The buffer holds 16 bytes total, so lim=16. Now pos=5, and we have 11 bytes
     * remaining (indices 5-15). The fast path check passes, and the test verifies
     * this boundary case works correctly.
     */
    @Test
    fun `readIdentifier off-by-one bug with exactly 11 bytes remaining`() {
        val baos = ByteArrayOutputStream()
        baos.write("abcd".toByteArray(StandardCharsets.US_ASCII))
        baos.write(0) // Null terminator for first string
        baos.write("0123456789".toByteArray(StandardCharsets.US_ASCII))
        baos.write(0) // Null terminator for second string

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, maxStringLength = 12, bufLen = 16)

        // Read first 4-char identifier
        val first = stream.readIdentifier()
        assertEquals("abcd", first)

        // This tests the boundary: exactly 11 bytes remain in buffer
        // (10 for string + 1 for terminator)
        val second = stream.readIdentifier()
        assertEquals("0123456789", second)
    }

    /**
     * Test for off-by-one bug in readUTF8String.
     *
     * Similar to readIdentifier but with 21-byte fast path.
     * Buffer size 26, first string 4 chars, second string 20 chars.
     */
    @Test
    fun `readUTF8String off-by-one bug with exactly 21 bytes remaining`() {
        val baos = ByteArrayOutputStream()
        baos.write("abcd".toByteArray(StandardCharsets.UTF_8))
        baos.write(0) // Null terminator for first string
        baos.write("01234567890123456789".toByteArray(StandardCharsets.UTF_8))
        baos.write(0) // Null terminator for second string

        val input = ByteArrayInputStream(baos.toByteArray())
        val stream = BInputStream(input, maxStringLength = 22, bufLen = 26)

        // Read first 4-char string
        val first = stream.readUTF8String()
        assertEquals("abcd", first)

        // This tests the boundary: exactly 21 bytes remain in buffer
        // (20 for string + 1 for terminator)
        val second = stream.readUTF8String()
        assertEquals("01234567890123456789", second)
    }
}
