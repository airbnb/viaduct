package viaduct.service.api.spi.globalid

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.spi.DecodedGlobalID

class GlobalIDCodecDefaultTest {
    @Test
    fun `serialize encodes the type name and local ID as Base64`() {
        assertEquals(base64("User:123"), GlobalIDCodecDefault.serialize("User", "123"))
    }

    @Test
    fun `serialize URL-encodes the local ID`() {
        assertEquals(base64("User:a%3Ab+c"), GlobalIDCodecDefault.serialize("User", "a:b c"))
    }

    @Test
    fun `deserialize restores a local ID with reserved characters`() {
        val localID = "a:b/c d&e=f%g"

        assertEquals(
            DecodedGlobalID("User", localID),
            GlobalIDCodecDefault.deserialize(GlobalIDCodecDefault.serialize("User", localID)),
        )
    }

    @Test
    fun `deserialize rejects a value that is not Base64`() {
        val error = assertThrows<IllegalArgumentException> { GlobalIDCodecDefault.deserialize("not base64!") }

        assertTrue(error.message!!.startsWith("Failed to deserialize GlobalID: \"not base64!\""))
    }

    @Test
    fun `deserialize rejects a decoded value without the type delimiter`() {
        val error = assertThrows<IllegalArgumentException> { GlobalIDCodecDefault.deserialize(base64("User")) }

        assertTrue(error.message!!.contains("Expected GlobalID to have format '<typeName>:<localID>'"))
    }

    private fun base64(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())
}
