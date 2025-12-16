package viaduct.service.runtime.globalid

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class GlobalIDCodecDefaultTest {
    @Test
    fun `serialize should encode type and local ID to Base64`() {
        val result = GlobalIDCodecDefault.serialize("User", "123")

        assertTrue(result.isNotEmpty())
        assertTrue(result.matches(Regex("^[A-Za-z0-9+/]+=*$")))
    }

    @Test
    fun `serialize should handle special characters in local ID`() {
        val result = GlobalIDCodecDefault.serialize("Product", "abc:def/ghi")

        assertTrue(result.isNotEmpty())
        assertTrue(result.matches(Regex("^[A-Za-z0-9+/]+=*$")))
    }

    @Test
    fun `serialize should handle empty local ID`() {
        val result = GlobalIDCodecDefault.serialize("User", "")

        assertTrue(result.isNotEmpty())
        assertTrue(result.matches(Regex("^[A-Za-z0-9+/]+=*$")))
    }

    @Test
    fun `serialize should URL-escape special characters`() {
        val result = GlobalIDCodecDefault.serialize("Type", "id with spaces")
        val (_, localID) = GlobalIDCodecDefault.deserialize(result)

        assertEquals("id with spaces", localID)
    }

    @Test
    fun `deserialize should decode Base64 to type and local ID`() {
        val serialized = GlobalIDCodecDefault.serialize("User", "123")
        val (typeName, localID) = GlobalIDCodecDefault.deserialize(serialized)

        assertEquals("User", typeName)
        assertEquals("123", localID)
    }

    @Test
    fun `deserialize should handle special characters in local ID`() {
        val originalLocalID = "abc:def/ghi"
        val serialized = GlobalIDCodecDefault.serialize("Product", originalLocalID)
        val (typeName, localID) = GlobalIDCodecDefault.deserialize(serialized)

        assertEquals("Product", typeName)
        assertEquals(originalLocalID, localID)
    }

    @Test
    fun `deserialize should handle empty local ID`() {
        val serialized = GlobalIDCodecDefault.serialize("User", "")
        val (typeName, localID) = GlobalIDCodecDefault.deserialize(serialized)

        assertEquals("User", typeName)
        assertTrue(localID.isEmpty())
    }

    @Test
    fun `serialize and deserialize should be reversible`() {
        val originalType = "Order"
        val originalID = "order-456"

        val serialized = GlobalIDCodecDefault.serialize(originalType, originalID)
        val (deserializedType, deserializedID) = GlobalIDCodecDefault.deserialize(serialized)

        assertEquals(originalType, deserializedType)
        assertEquals(originalID, deserializedID)
    }

    @Test
    fun `serialize and deserialize should handle Unicode characters`() {
        val originalType = "User"
        val originalID = "用户123"

        val serialized = GlobalIDCodecDefault.serialize(originalType, originalID)
        val (deserializedType, deserializedID) = GlobalIDCodecDefault.deserialize(serialized)

        assertEquals(originalType, deserializedType)
        assertEquals(originalID, deserializedID)
    }

    @Test
    fun `deserialize should throw exception for invalid Base64`() {
        val exception = assertThrows<IllegalArgumentException> {
            GlobalIDCodecDefault.deserialize("not-valid-base64!!!")
        }
        assertTrue(exception.message?.contains("Failed to deserialize GlobalID") ?: false)
    }

    @Test
    fun `deserialize should throw exception for malformed format without delimiter`() {
        val invalidGlobalID = Base64.getEncoder().encodeToString("NoDelimiterHere".toByteArray())

        val exception = assertThrows<IllegalArgumentException> {
            GlobalIDCodecDefault.deserialize(invalidGlobalID)
        }
        assertTrue(exception.message?.contains("Failed to deserialize GlobalID") ?: false)
        assertTrue(exception.message?.contains("Expected GlobalID to have format") ?: false)
    }

    @Test
    fun `deserialize should include original globalID in error message`() {
        val invalidGlobalID = "invalid-data"

        val exception = assertThrows<IllegalArgumentException> {
            GlobalIDCodecDefault.deserialize(invalidGlobalID)
        }
        assertTrue(exception.message?.contains(invalidGlobalID) ?: false)
    }
}
