package viaduct.service.runtime.globalid

import com.google.common.net.UrlEscapers
import java.net.URLDecoder
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.runtime.globalid.GlobalIDCodecDefaultTest.LegacyGlobalID.fromGlobalId
import viaduct.service.runtime.globalid.GlobalIDCodecDefaultTest.LegacyGlobalID.getGlobalId
import viaduct.service.runtime.globalid.GlobalIDCodecDefaultTest.LegacyGlobalID.internalIdFromGlobalId
import viaduct.service.runtime.globalid.GlobalIDCodecDefaultTest.LegacyGlobalID.typeNameFromGlobalId

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

    /**
     * Legacy compatibility tests to ensure GlobalIDCodecDefault is interoperable with
     * the legacy GlobalID functions from com.airbnb.viaduct.types.
     *
     * These tests verify that:
     * 1. GlobalIDs serialized by GlobalIDCodecDefault can be deserialized by legacy functions
     * 2. GlobalIDs serialized by legacy functions can be deserialized by GlobalIDCodecDefault
     * 3. Both implementations produce identical output for the same input
     */
    @Nested
    inner class LegacyCompatibility {
        @Test
        fun `serialize produces same output as legacy getGlobalId for simple IDs`() {
            val testCases = listOf(
                "TestType" to "123",
                "TestType" to "abc",
                "TestType" to "test-id-123",
                "TestType" to "a",
                "TestType" to "12345678901234567890"
            )

            testCases.forEach { (typeName, internalId) ->
                val modernSerialized = GlobalIDCodecDefault.serialize(typeName, internalId)
                val legacySerialized = getGlobalId(typeName, internalId)

                assertEquals(
                    legacySerialized,
                    modernSerialized,
                    "Modern serialize should produce identical output to legacy getGlobalId for internalId='$internalId'"
                )
            }
        }

        @Test
        fun `serialize produces same output as legacy getGlobalId for various type names`() {
            val typeNames = listOf("User", "Listing", "Reservation", "Host", "Guest", "Review", "Message")
            val internalId = "test-internal-id-123"

            typeNames.forEach { typeName ->
                val modernSerialized = GlobalIDCodecDefault.serialize(typeName, internalId)
                val legacySerialized = getGlobalId(typeName, internalId)

                assertEquals(
                    legacySerialized,
                    modernSerialized,
                    "Modern serialize should match legacy for typeName='$typeName'"
                )
            }
        }

        @Test
        fun `deserialize can read GlobalIDs created by legacy getGlobalId`() {
            val testCases = listOf(
                "TestType" to "123",
                "User" to "user-12345",
                "Listing" to "listing-67890",
                "Reservation" to "HMAK12345"
            )

            testCases.forEach { (typeName, internalId) ->
                val legacySerialized = getGlobalId(typeName, internalId)

                val (deserializedType, deserializedId) = GlobalIDCodecDefault.deserialize(legacySerialized)

                assertEquals(typeName, deserializedType, "Type name should match for $typeName:$internalId")
                assertEquals(internalId, deserializedId, "Internal ID should match for $typeName:$internalId")
            }
        }

        @Test
        fun `legacy functions can read GlobalIDs created by modern serialize`() {
            val testCases = listOf(
                "TestType" to "123",
                "User" to "user-12345",
                "Listing" to "listing-67890",
                "Reservation" to "HMAK12345"
            )

            testCases.forEach { (typeName, internalId) ->
                val modernSerialized = GlobalIDCodecDefault.serialize(typeName, internalId)

                val legacyTypeName = typeNameFromGlobalId(modernSerialized)
                val legacyInternalId = internalIdFromGlobalId(modernSerialized)

                assertEquals(typeName, legacyTypeName, "Legacy typeNameFromGlobalId should extract correct type")
                assertEquals(internalId, legacyInternalId, "Legacy internalIdFromGlobalId should extract correct ID")
            }
        }

        @Test
        fun `roundtrip modern serialize to legacy deserialize`() {
            val testCases = listOf(
                "RoundtripType" to "simple-id",
                "User" to "12345",
                "Complex" to "id-with-dashes-and-numbers-123"
            )

            testCases.forEach { (typeName, internalId) ->
                val serialized = GlobalIDCodecDefault.serialize(typeName, internalId)
                val (legacyType, legacyId) = fromGlobalId(serialized)

                assertEquals(typeName, legacyType, "Roundtrip type should match for $typeName")
                assertEquals(internalId, legacyId, "Roundtrip ID should match for $internalId")
            }
        }

        @Test
        fun `roundtrip legacy serialize to modern deserialize`() {
            val testCases = listOf(
                "RoundtripType" to "simple-id",
                "User" to "12345",
                "Complex" to "id-with-dashes-and-numbers-123"
            )

            testCases.forEach { (typeName, internalId) ->
                val serialized = getGlobalId(typeName, internalId)
                val (modernType, modernId) = GlobalIDCodecDefault.deserialize(serialized)

                assertEquals(typeName, modernType, "Roundtrip type should match for $typeName")
                assertEquals(internalId, modernId, "Roundtrip ID should match for $internalId")
            }
        }

        @Test
        fun `handles special characters consistently with legacy`() {
            val testCases = listOf(
                "id with spaces",
                "id:with:colons",
                "id/with/slashes",
                "id+with+plus",
                "id=with=equals",
                "id&with&ampersand",
                "id?with?question",
                "id#with#hash",
                "id%with%percent",
                "café",
                "用户123",
                "emoji🎊",
                "" // empty string
            )

            testCases.forEach { internalId ->
                val typeName = "SpecialCharType"

                // Verify both implementations produce the same serialized output
                val modernSerialized = GlobalIDCodecDefault.serialize(typeName, internalId)
                val legacySerialized = getGlobalId(typeName, internalId)
                assertEquals(
                    legacySerialized,
                    modernSerialized,
                    "Serialization should match for special char: '$internalId'"
                )

                // Verify modern can deserialize legacy
                val (modernType, modernId) = GlobalIDCodecDefault.deserialize(legacySerialized)
                assertEquals(typeName, modernType)
                assertEquals(internalId, modernId)

                // Verify legacy can deserialize modern
                val legacyType = typeNameFromGlobalId(modernSerialized)
                val legacyId = internalIdFromGlobalId(modernSerialized)
                assertEquals(typeName, legacyType)
                assertEquals(internalId, legacyId)
            }
        }

        @Test
        fun `handles production-like GlobalID patterns`() {
            val productionPatterns = listOf(
                "User" to "12345678",
                "Listing" to "987654321",
                "Reservation" to "HMAK12345",
                "Host" to "user-uuid-1234-5678-abcd",
                "Message" to "msg_2024_01_15_abc123",
                "Review" to "rev:listing:12345:user:67890"
            )

            productionPatterns.forEach { (typeName, internalId) ->
                val modernSerialized = GlobalIDCodecDefault.serialize(typeName, internalId)
                val legacySerialized = getGlobalId(typeName, internalId)

                assertEquals(
                    legacySerialized,
                    modernSerialized,
                    "Production pattern should match: $typeName:$internalId"
                )

                // Verify bidirectional compatibility
                val (fromModern, _) = GlobalIDCodecDefault.deserialize(legacySerialized)
                val fromLegacy = typeNameFromGlobalId(modernSerialized)

                assertEquals(typeName, fromModern)
                assertEquals(typeName, fromLegacy)
            }
        }

        @Test
        fun `handles numeric IDs consistently with legacy`() {
            val numericIds = listOf(
                "0",
                "1",
                "123456789",
                "999999999999999",
                "-1",
                "-12345"
            )

            numericIds.forEach { internalId ->
                val typeName = "NumericType"
                val modernSerialized = GlobalIDCodecDefault.serialize(typeName, internalId)
                val legacySerialized = getGlobalId(typeName, internalId)

                assertEquals(
                    legacySerialized,
                    modernSerialized,
                    "Numeric ID should match: $internalId"
                )

                // Verify roundtrip
                val (type1, id1) = GlobalIDCodecDefault.deserialize(legacySerialized)
                val (type2, id2) = fromGlobalId(modernSerialized)

                assertEquals(typeName, type1)
                assertEquals(typeName, type2)
                assertEquals(internalId, id1)
                assertEquals(internalId, id2)
            }
        }

        @Test
        fun `handles UUID-style IDs consistently with legacy`() {
            val uuidIds = listOf(
                "550e8400-e29b-41d4-a716-446655440000",
                "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
                "f47ac10b-58cc-4372-a567-0e02b2c3d479"
            )

            uuidIds.forEach { internalId ->
                val typeName = "UUIDType"
                val modernSerialized = GlobalIDCodecDefault.serialize(typeName, internalId)
                val legacySerialized = getGlobalId(typeName, internalId)

                assertEquals(
                    legacySerialized,
                    modernSerialized,
                    "UUID ID should match: $internalId"
                )

                // Verify full roundtrip through both systems
                val (modernType, modernId) = GlobalIDCodecDefault.deserialize(legacySerialized)
                val legacyType = typeNameFromGlobalId(modernSerialized)
                val legacyId = internalIdFromGlobalId(modernSerialized)

                assertEquals(typeName, modernType)
                assertEquals(typeName, legacyType)
                assertEquals(internalId, modernId)
                assertEquals(internalId, legacyId)
            }
        }
    }

    /**
     * Legacy GlobalID functions inlined from com.airbnb.viaduct.types.GlobalID
     * for testing backward compatibility without depending on internal packages.
     */
    private object LegacyGlobalID {
        private const val DELIMITER = ":"
        private val escaper by lazy { UrlEscapers.urlFormParameterEscaper() }

        fun getGlobalId(
            typeName: String,
            internalID: String
        ): String =
            Base64.getEncoder().encodeToString(
                "$typeName$DELIMITER${escaper.escape(internalID)}".toByteArray()
            )

        fun fromGlobalId(id: String): Pair<String, String> {
            val parts = String(Base64.getDecoder().decode(id)).split(DELIMITER)
            return Pair(parts[0], URLDecoder.decode(parts[1], "UTF-8"))
        }

        fun typeNameFromGlobalId(id: String): String? = runCatching { fromGlobalId(id).first }.getOrNull()

        fun internalIdFromGlobalId(id: String): String? = runCatching { fromGlobalId(id).second }.getOrNull()
    }
}
