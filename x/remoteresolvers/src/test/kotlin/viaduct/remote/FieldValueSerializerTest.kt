package viaduct.remote

import graphql.schema.GraphQLObjectType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.ResolvedEngineObjectData

/**
 * Unit tests for [FieldValueSerializer]: value/argument round-trips and the
 * object-typed-value guard.
 */
class FieldValueSerializerTest {
    private fun roundTripValue(value: Any?): Any? = FieldValueSerializer.deserializeValue(FieldValueSerializer.serializeValue(value))

    @Test
    fun `null round-trips`() {
        assertNull(roundTripValue(null))
    }

    @Test
    fun `boolean round-trips`() {
        assertEquals(true, roundTripValue(true))
        assertEquals(false, roundTripValue(false))
    }

    @Test
    fun `int round-trips`() {
        assertEquals(42, roundTripValue(42))
    }

    @Test
    fun `double round-trips`() {
        assertEquals(3.14, roundTripValue(3.14))
    }

    @Test
    fun `string round-trips`() {
        assertEquals("hello", roundTripValue("hello"))
    }

    @Test
    fun `list of scalars round-trips`() {
        // Jackson reads JSON integers back as Int here, so an Int list compares equal.
        assertEquals(listOf(1, 2, 3), roundTripValue(listOf(1, 2, 3)))
        assertEquals(listOf("a", "b"), roundTripValue(listOf("a", "b")))
    }

    @Test
    fun `map of scalars round-trips`() {
        val map = mapOf("name" to "Luke", "age" to 23, "jedi" to true)
        assertEquals(map, roundTripValue(map))
    }

    @Test
    fun `arguments round-trip`() {
        val arguments = mapOf("first" to 10, "after" to "cursor", "includeDrafts" to false)
        val bytes = FieldValueSerializer.serializeArguments(arguments)
        assertEquals(arguments, FieldValueSerializer.deserializeArguments(bytes))
    }

    @Test
    fun `empty argument bytes deserialize to an empty map`() {
        FieldValueSerializer.deserializeArguments(ByteArray(0)) shouldBe emptyMap()
    }

    @Test
    fun `object-typed return value is rejected`() {
        // An EngineObjectData has no GraphQL type identity on the wire, so the serializer
        // must reject it rather than silently produce an unreconstructable payload.
        val objectData = ResolvedEngineObjectData.Builder(
            GraphQLObjectType.newObject().name("Character").build()
        ).put("id", "1").build()

        val ex = assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(objectData)
        }
        ex.message.shouldContain("ResolvedEngineObjectData")
    }

    @Test
    fun `arbitrary non-JSON-friendly value is rejected`() {
        assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(NonJsonFriendly())
        }
    }

    @Test
    fun `object nested inside a list is rejected`() {
        val objectData = ResolvedEngineObjectData.Builder(
            GraphQLObjectType.newObject().name("Character").build()
        ).build()
        // The guard walks the structure, so a nested object inside an otherwise-fine list
        // is still caught.
        assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(listOf("ok", objectData))
        }
    }

    @Test
    fun `object nested inside a map is rejected`() {
        val objectData = ResolvedEngineObjectData.Builder(
            GraphQLObjectType.newObject().name("Character").build()
        ).build()
        assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(mapOf("nested" to objectData))
        }
    }

    @Test
    fun `value envelope tolerates a bare null payload`() {
        // serializeValue wraps results in a {"v": ...} envelope so a top-level null is
        // unambiguous on the wire.
        val bytes = FieldValueSerializer.serializeValue(null)
        assertTrue(bytes.isNotEmpty())
        assertNull(FieldValueSerializer.deserializeValue(bytes))
    }

    private class NonJsonFriendly
}
