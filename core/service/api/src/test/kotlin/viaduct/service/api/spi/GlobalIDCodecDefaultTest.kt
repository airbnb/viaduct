package viaduct.service.api.spi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

internal class GlobalIDCodecDefaultTest {
    @Test
    fun `uses the canonical GlobalID wire format`() {
        assertEquals("VXNlcjoxMjM=", GlobalIDCodecDefault.serialize("User", "123"))
        assertEquals(
            DecodedGlobalID("User", "123"),
            GlobalIDCodecDefault.deserialize("VXNlcjoxMjM=")
        )
    }

    @Test
    fun `round trips local IDs with reserved characters and unicode`() {
        val localID = "room:東京 +/%?=42"

        val globalID = GlobalIDCodecDefault.serialize("Listing", localID)

        assertEquals(DecodedGlobalID("Listing", localID), GlobalIDCodecDefault.deserialize(globalID))
    }

    @Test
    fun `rejects a GlobalID without a type separator`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GlobalIDCodecDefault.deserialize("VXNlcg==")
        }

        assertTrue(error.message!!.contains("Expected GlobalID to have format"))
    }

    @Test
    fun `rejects malformed Base64`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            GlobalIDCodecDefault.deserialize("not-base64!")
        }

        assertTrue(error.message!!.contains("Failed to deserialize GlobalID"))
    }
}
