package viaduct.graphql.schema.binary

import java.io.Closeable
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

private const val BUF_SIZE = 128 * 1024

/**
 * Buffered binary output stream for writing schema files.
 *
 * Provides efficient writing of integers, strings, and identifiers
 * with automatic buffer management and word alignment tracking.
 */
internal class BOutputStream(
    private val output: OutputStream,
    /** Buffer size, defaults to BUF_SIZE. Exposed for testing. */
    private val bufLen: Int = BUF_SIZE
) : Closeable {
    private val buf = ByteBuffer.allocate(bufLen).order(ByteOrder.LITTLE_ENDIAN)

    val offset: Int get() = mOffset
    private var mOffset: Int = 0

    private fun assureCapacity(sz: Int): ByteBuffer {
        if (sz > bufLen) {
            throw IllegalStateException("Write size ($sz) exceeds buffer capacity ($bufLen)")
        }
        if (buf.position() + sz >= buf.capacity()) {
            output.write(buf.array(), 0, buf.position())
            buf.clear()
        }
        mOffset += sz
        return this.buf
    }

    fun write(b: Int) {
        assureCapacity(1)
        buf.put(b.toByte())
    }

    fun writeInt(i: Int) {
        assureCapacity(WORD_SIZE).putInt(i)
    }

    fun writeLong(l: Long) {
        assureCapacity(2 * WORD_SIZE).putLong(l)
    }

    /**
     * Writes a null-terminated ASCII identifier string.
     */
    fun writeIdentifier(s: String) {
        assureCapacity(s.length + 1)
        for (c in s) buf.put(c.code.toByte())
        buf.put(0) // Null terminator
    }

    /**
     * Writes a null-terminated UTF-8 string.
     */
    fun writeUTF8String(s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        assureCapacity(bytes.size + 1)
        buf.put(bytes)
        buf.put(0)
    }

    fun pad() {
        val remainder = mOffset.rem(WORD_SIZE)
        val toWrite = if (remainder == 0) 0 else WORD_SIZE - remainder
        assureCapacity(toWrite)
        repeat(toWrite) { buf.put(0) }
    }

    override fun close() {
        output.write(buf.array(), 0, buf.position())
        output.close()
    }
}
