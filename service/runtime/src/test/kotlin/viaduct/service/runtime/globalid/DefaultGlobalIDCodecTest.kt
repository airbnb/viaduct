package viaduct.service.runtime.globalid

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DefaultGlobalIDCodecTest {
    private val codec = DefaultGlobalIDCodec()

    @Test
    fun `serialize should encode type and local ID to Base64`() {
        val result = codec.serialize("User", "123")

        assertTrue(result.isNotEmpty())
        assertTrue(result.matches(Regex("^[A-Za-z0-9+/]+=*$")))
    }

    @Test
    fun `serialize should handle special characters in local ID`() {
        val result = codec.serialize("Product", "abc:def/ghi")

        assertTrue(result.isNotEmpty())
        assertTrue(result.matches(Regex("^[A-Za-z0-9+/]+=*$")))
    }

    @Test
    fun `deserialize should decode Base64 to type and local ID`() {
        val serialized = codec.serialize("User", "123")
        val (typeName, localID) = codec.deserialize(serialized)

        assertEquals("User", typeName)
        assertEquals("123", localID)
    }

    @Test
    fun `deserialize should handle special characters in local ID`() {
        val originalLocalID = "abc:def/ghi"
        val serialized = codec.serialize("Product", originalLocalID)
        val (typeName, localID) = codec.deserialize(serialized)

        assertEquals("Product", typeName)
        assertEquals(originalLocalID, localID)
    }

    @Test
    fun `serialize and deserialize should be reversible`() {
        val originalType = "Order"
        val originalID = "order-456"

        val serialized = codec.serialize(originalType, originalID)
        val (deserializedType, deserializedID) = codec.deserialize(serialized)

        assertEquals(originalType, deserializedType)
        assertEquals(originalID, deserializedID)
    }

    @Test
    fun `deserialize should throw exception for invalid Base64`() {
        val exception = assertThrows<IllegalArgumentException> {
            codec.deserialize("not-valid-base64!!!")
        }
        assertTrue(exception.message?.contains("Failed to deserialize GlobalID") ?: false)
    }

    @Test
    fun `deserialize should throw exception for malformed format`() {
        val invalidGlobalID = Base64.getEncoder().encodeToString("NoDelimiterHere".toByteArray())

        val exception = assertThrows<IllegalArgumentException> {
            codec.deserialize(invalidGlobalID)
        }
        assertTrue(exception.message?.contains("Failed to deserialize GlobalID") ?: false)
    }
}
