package viaduct.graphql.schema.binary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for BOutputStream functionality.
 *
 * These tests verify that BOutputStream correctly writes various data types,
 * handles buffer flushing, and tracks write offset properly.
 */
class BOutputStreamTest {
    @Test
    fun `Large write triggers buffer flush`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        // Write enough data to exceed buffer size (16KB)
        repeat(5000) {
            out.writeInt(it)
        }
        out.close()

        // Verify all data was written
        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = BInputStream(bais, 1024)
        repeat(5000) {
            assertEquals(it, input.readInt())
        }
    }

    @Test
    fun `BOutputStream offset tracks written bytes`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        assertEquals(0, out.offset)
        out.writeInt(42)
        assertEquals(4, out.offset)
        out.writeInt(43)
        assertEquals(8, out.offset)
        out.write(0xFF)
        assertEquals(9, out.offset)
    }

    @Test
    fun `writeLong writes 8 bytes`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        out.writeLong(123456789L)
        out.close()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = BInputStream(bais, 1024)
        assertEquals(123456789L, input.readLong())
    }

    @Test
    fun `writeLong handles large values`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        val largeValue = 9223372036854775807L // Long.MAX_VALUE
        out.writeLong(largeValue)
        out.close()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = BInputStream(bais, 1024)
        assertEquals(largeValue, input.readLong())
    }

    @Test
    fun `writeLong handles negative values`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        val negativeValue = -123456789L
        out.writeLong(negativeValue)
        out.close()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = BInputStream(bais, 1024)
        assertEquals(negativeValue, input.readLong())
    }

    @Test
    fun `write single byte works correctly`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        out.write(0x42)
        out.close()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = BInputStream(bais, 1024)
        assertEquals(0x42, input.read())
    }

    @Test
    fun `write multiple single bytes`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        out.write(0x01)
        out.write(0x02)
        out.write(0x03)
        out.write(0xFF)
        out.close()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = BInputStream(bais, 1024)
        assertEquals(0x01, input.read())
        assertEquals(0x02, input.read())
        assertEquals(0x03, input.read())
        assertEquals(0xFF, input.read())
    }

    @Test
    fun `Mixed write operations maintain correct order`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        out.write(0x01)
        out.writeInt(42)
        out.writeLong(123456789L)
        out.write(0xFF)
        out.close()

        val bais = ByteArrayInputStream(baos.toByteArray())
        val input = BInputStream(bais, 1024)
        assertEquals(0x01, input.read())
        assertEquals(42, input.readInt())
        assertEquals(123456789L, input.readLong())
        assertEquals(0xFF, input.read())
    }

    @Test
    fun `Offset increments correctly with mixed operations`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        assertEquals(0, out.offset)
        out.write(0x01) // +1
        assertEquals(1, out.offset)
        out.writeInt(42) // +4
        assertEquals(5, out.offset)
        out.writeLong(123L) // +8
        assertEquals(13, out.offset)
        out.write(0xFF) // +1
        assertEquals(14, out.offset)
    }

    @Test
    fun `Padding aligns to word boundaries`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        // Write 1 byte, then pad
        out.write(0x01)
        assertEquals(1, out.offset)
        out.pad()
        // Should pad to next 4-byte boundary (4 bytes total)
        assertEquals(4, out.offset)

        // Write 2 more bytes, then pad
        out.write(0x02)
        out.write(0x03)
        assertEquals(6, out.offset)
        out.pad()
        // Should pad to next 4-byte boundary (8 bytes total)
        assertEquals(8, out.offset)
    }

    @Test
    fun `Padding when already aligned does nothing`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos)

        out.writeInt(42) // Already 4-byte aligned
        assertEquals(4, out.offset)
        out.pad()
        assertEquals(4, out.offset) // No change
    }

    /**
     * Test that assureCapacity throws IllegalStateException when writing a string
     * larger than buffer capacity.
     *
     * This test uses a 10-byte buffer and tries to write a 10-character identifier.
     * The identifier itself is 10 bytes, plus 1 byte for the terminator = 11 bytes total.
     * This should trigger the exception since 11 > 10.
     */
    @Test
    fun `writeIdentifier throws when identifier plus terminator exceeds buffer`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos, bufLen = 10)

        // This identifier is 10 bytes + 1 byte terminator = 11 bytes total
        // The buffer is only 10 bytes, so assureCapacity should throw
        val tenCharIdentifier = "0123456789" // exactly 10 characters

        val exception = assertThrows(IllegalStateException::class.java) {
            out.writeIdentifier(tenCharIdentifier)
        }
        assertTrue(exception.message!!.contains("exceeds buffer capacity")) {
            "Expected message to contain 'exceeds buffer capacity' but was: ${exception.message}"
        }
    }

    /**
     * Similar test for writeUTF8String with a string that exceeds buffer capacity.
     */
    @Test
    fun `writeUTF8String throws when string plus terminator exceeds buffer`() {
        val baos = ByteArrayOutputStream()
        val out = BOutputStream(baos, bufLen = 10)

        // This string is 10 bytes + 1 byte null terminator = 11 bytes total
        val tenCharString = "0123456789" // exactly 10 characters

        val exception = assertThrows(IllegalStateException::class.java) {
            out.writeUTF8String(tenCharString)
        }
        assertTrue(exception.message!!.contains("exceeds buffer capacity")) {
            "Expected message to contain 'exceeds buffer capacity' but was: ${exception.message}"
        }
    }
}
