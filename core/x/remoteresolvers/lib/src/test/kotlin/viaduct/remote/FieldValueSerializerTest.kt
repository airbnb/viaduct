@file:Suppress("ForbiddenImport")

package viaduct.remote

import graphql.schema.GraphQLObjectType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks

/**
 * Unit tests for [FieldValueSerializer]: round-trips for every field-return kind (scalar, null, node
 * reference, resolved object, and lists thereof), argument round-trips, and the guards for genuinely
 * non-serializable values. The value encoding itself lives in [EngineObjectDataSerializer] and is
 * covered by [EngineObjectDataSerializerTest].
 */
class FieldValueSerializerTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { test: String }
        type Character implements Node {
            id: ID!
            name: String
            age: Int
            isAdult: Boolean
            species: Species
            homeworld: Planet
        }
        type Species {
            id: ID!
            name: String
        }
        type Planet {
            id: ID!
            name: String
        }
        """.trimIndent()
    )

    // A real EngineExecutionContextImpl over the schema, so deserialize can rebuild references and
    // objects against the live types (as it would on the engine side).
    private val context: EngineExecutionContext = ContextMocks(schema).engineExecutionContext

    private fun objectType(name: String): GraphQLObjectType = schema.schema.getObjectType(name)

    private fun roundTrip(value: Any?): Any? = FieldValueSerializer.deserializeValue(FieldValueSerializer.serializeValue(value), context)

    @Test
    fun `null round-trips`() {
        assertNull(roundTrip(null))
    }

    @Test
    fun `boolean round-trips`() {
        assertEquals(true, roundTrip(true))
        assertEquals(false, roundTrip(false))
    }

    @Test
    fun `int round-trips`() {
        assertEquals(42, roundTrip(42))
    }

    @Test
    fun `double round-trips`() {
        assertEquals(3.14, roundTrip(3.14))
    }

    @Test
    fun `string round-trips`() {
        assertEquals("hello", roundTrip("hello"))
    }

    @Test
    fun `list of scalars round-trips`() {
        // Jackson reads JSON integers back as Int here, so an Int list compares equal.
        assertEquals(listOf(1, 2, 3), roundTrip(listOf(1, 2, 3)))
        assertEquals(listOf("a", "b"), roundTrip(listOf("a", "b")))
    }

    @Test
    fun `map of scalars round-trips`() {
        val map = mapOf("name" to "Luke", "age" to 23, "jedi" to true)
        assertEquals(map, roundTrip(map))
    }

    @Test
    fun `node reference round-trips as a NodeReference of the same id and type`() {
        val ref = context.createNodeReference("Species:42", objectType("Species"))
        val result = roundTrip(ref)
        assertTrue(result is NodeReference, "Expected a NodeReference, got ${result?.let { it::class }}")
        result as NodeReference
        assertEquals("Species:42", result.id, "id should survive the round-trip")
        assertEquals("Species", result.type.name, "concrete type should survive the round-trip")
    }

    @Test
    fun `resolved object round-trips as an EngineObjectData of the same type and fields`() {
        val obj = ResolvedEngineObjectData.Builder(objectType("Character"))
            .put("name", "Luke")
            .put("age", 23)
            .build()
        val result = roundTrip(obj)
        assertTrue(result is EngineObjectData.Sync, "Expected an EngineObjectData.Sync, got ${result?.let { it::class }}")
        result as EngineObjectData.Sync
        assertEquals("Character", result.type.name, "concrete type should survive")
        assertEquals("Luke", result.get("name"))
        assertEquals(23, result.get("age"))
    }

    @Test
    fun `list of objects round-trips`() {
        val obj1 = ResolvedEngineObjectData.Builder(objectType("Character")).put("name", "Luke").build()
        val obj2 = ResolvedEngineObjectData.Builder(objectType("Character")).put("name", "Leia").build()
        val result = roundTrip(listOf(obj1, obj2))
        assertTrue(result is List<*>, "Expected a List, got ${result?.let { it::class }}")
        result as List<*>
        assertEquals(2, result.size)
        assertEquals("Luke", (result[0] as EngineObjectData.Sync).get("name"))
        assertEquals("Leia", (result[1] as EngineObjectData.Sync).get("name"))
    }

    @Test
    fun `list mixing a scalar, a null, and a node reference is tagged per element`() {
        val ref = context.createNodeReference("Species:1", objectType("Species"))
        val result = roundTrip(listOf("scalar", null, ref))
        result as List<*>
        assertEquals("scalar", result[0])
        assertNull(result[1])
        assertEquals("Species:1", (result[2] as NodeReference).id)
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
    fun `null round-trips through non-empty tagged bytes`() {
        val bytes = FieldValueSerializer.serializeValue(null)
        assertTrue(bytes.isNotEmpty())
        assertNull(FieldValueSerializer.deserializeValue(bytes, context))
    }

    @Test
    fun `an arbitrary non-serializable value is rejected`() {
        val ex = assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(NonSerializable())
        }
        ex.message.shouldContain("NonSerializable")
    }

    @Test
    fun `an object nested inside a map is rejected`() {
        // Map values ship untagged as a JSON leaf, so an object hidden in a map cannot be
        // reconstructed on the wire and must be rejected rather than silently mangled.
        val objectData = ResolvedEngineObjectData.Builder(objectType("Character")).build()
        assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(mapOf("nested" to objectData))
        }
    }

    @Test
    fun `object of an interface-implementing type round-trips as its concrete type`() {
        // Even when a field is declared as an interface (Node), the value carries its concrete type
        // (Character) — that is what the tag records and reconstructs, not the interface.
        val obj = ResolvedEngineObjectData.Builder(objectType("Character")).put("name", "Luke").build()
        val result = roundTrip(obj) as EngineObjectData.Sync
        assertEquals("Character", result.type.name, "concrete type (not the Node interface) should round-trip")
        assertEquals("Luke", result.get("name"))
    }

    @Test
    fun `a returned object holding a nested node reference is rejected, not hung`() {
        // Regression for the nested-NodeReference serialize hang: a Character whose `species` selection
        // is a NodeReference must fail fast (NodeEngineObjectDataImpl is both NodeReference and
        // EngineObjectData; recursing would await a resolution that never completes on the serialize path).
        val character = ResolvedEngineObjectData.Builder(objectType("Character"))
            .put("name", "Luke")
            .put("species", context.createNodeReference("Species:1", objectType("Species")))
            .build()
        val ex = assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(character)
        }
        ex.message.shouldContain("nested NodeReference")
    }

    @Test
    fun `an unresolved root field reference is rejected, not hung`() {
        // Regression for the same class of bug as the nested-NodeReference case, but for
        // RootFieldReference: ObjectRootFieldReference is also an EngineObjectData, but unlike
        // NodeReference it has no dedicated wire representation here, so it must be rejected
        // synchronously rather than awaiting a resolution that never completes on the serialize path.
        val ref = context.createRootFieldReference(listOf("test"), objectType("Species"), emptyMap())
        val ex = assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(ref)
        }
        ex.message.shouldContain("unresolved EngineObjectData")
    }

    @Test
    fun `a returned object holding a nested root field reference is rejected, not hung`() {
        // Same regression, nested: a Character whose `species` selection is an unresolved
        // RootFieldReference must fail fast rather than hang.
        val character = ResolvedEngineObjectData.Builder(objectType("Character"))
            .put("name", "Luke")
            .put("species", context.createRootFieldReference(listOf("test"), objectType("Species"), emptyMap()))
            .build()
        val ex = assertThrows<UnsupportedOperationException> {
            FieldValueSerializer.serializeValue(character)
        }
        ex.message.shouldContain("unresolved EngineObjectData")
    }

    @Test
    fun `a returned object with a nested object child round-trips`() {
        val character = ResolvedEngineObjectData.Builder(objectType("Character"))
            .put("name", "Luke")
            .put("homeworld", ResolvedEngineObjectData.Builder(objectType("Planet")).put("name", "Tatooine").build())
            .build()
        val result = roundTrip(character) as EngineObjectData.Sync
        assertEquals("Character", result.type.name)
        val homeworld = result.get("homeworld") as EngineObjectData.Sync
        assertEquals("Planet", homeworld.type.name, "a nested object keeps its own concrete type")
        assertEquals("Tatooine", homeworld.get("name"))
    }

    @Test
    fun `nested lists and a list containing a null round-trip`() {
        assertEquals(listOf(listOf(1, 2), listOf(3)), roundTrip(listOf(listOf(1, 2), listOf(3))))
        assertEquals(listOf(null, "x"), roundTrip(listOf(null, "x")))
    }

    @Test
    fun `an unknown envelope key and a truncated node reference are rejected with clear errors`() {
        val unknownKey = EngineObjectDataSerializer.wrap(mapOf("x" to emptyMap<String, Any?>()))
        val unknown = assertThrows<UnsupportedOperationException> { FieldValueSerializer.deserializeValue(unknownKey, context) }
        unknown.message.shouldContain("Unknown remote value envelope key 'x'")

        val refMissingId = EngineObjectDataSerializer.wrap(mapOf("r" to mapOf("t" to "Species")))
        val ex = assertThrows<UnsupportedOperationException> { FieldValueSerializer.deserializeValue(refMissingId, context) }
        ex.message.shouldContain("missing its 'id'")
    }

    private class NonSerializable
}
