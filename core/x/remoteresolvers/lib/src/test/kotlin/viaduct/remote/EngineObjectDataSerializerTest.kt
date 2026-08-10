@file:Suppress("ForbiddenImport")

package viaduct.remote

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks

/**
 * Unit tests for [EngineObjectDataSerializer], the wire codec shared by node payloads, field values
 * and required-selection-set values.
 *
 * The behaviour that matters here is that a *nested* object keeps its own concrete GraphQL type: it
 * used to be rebuilt under a `RemoteNestedObject` placeholder, which broke every consumer that reads
 * type identity below the root. The tests also pin the versioned payload root, which is what makes a
 * build mismatch between the two processes fail loudly in both directions.
 */
class EngineObjectDataSerializerTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { test: String }
        interface Named { name: String }
        type Character implements Node & Named {
            id: ID!
            name: String
            homeworld: Planet
            friends: [Character]
            metadata: JSON
        }
        type Planet implements Named {
            id: ID!
            name: String
            climate: Climate
        }
        type Climate { summary: String }
        type Droid implements Named { name: String }
        union Being = Character | Droid
        """.trimIndent()
    )

    private val graphQLSchema: GraphQLSchema = schema.schema
    private val context = ContextMocks(schema).engineExecutionContext
    private val objectMapper = jacksonObjectMapper()

    private fun type(name: String): GraphQLObjectType = graphQLSchema.getObjectType(name)

    private fun eod(
        typeName: String,
        vararg fields: Pair<String, Any?>
    ): EngineObjectData.Sync = ResolvedEngineObjectData.Builder(type(typeName)).also { b -> fields.forEach { (k, v) -> b.put(k, v) } }.build()

    private fun roundTrip(
        data: EngineObjectData.Sync,
        expected: String = data.type.name
    ): EngineObjectData.Sync = EngineObjectDataSerializer.deserialize(EngineObjectDataSerializer.serialize(data), graphQLSchema, expected)

    @Test
    fun `a flat object round-trips with its type and scalar fields`() {
        val result = roundTrip(eod("Character", "name" to "Luke", "id" to "Character:1"))
        assertEquals("Character", result.type.name)
        assertEquals("Luke", result.get("name"))
        assertEquals("Character:1", result.get("id"))
    }

    @Test
    fun `a nested object keeps its own concrete type, not a placeholder`() {
        val result = roundTrip(
            eod("Character", "name" to "Luke", "homeworld" to eod("Planet", "name" to "Tatooine"))
        )
        val homeworld = result.get("homeworld") as EngineObjectData.Sync
        assertEquals("Planet", homeworld.type.name)
        assertEquals("Tatooine", homeworld.get("name"))
    }

    @Test
    fun `type identity survives three levels of nesting`() {
        // The shape that motivated this change: a wrapper response whose payload is two levels down,
        // which is the common Airbnb resolver shape (Query.fetchX -> FetchXResponse -> view -> ...).
        val result = roundTrip(
            eod(
                "Character",
                "homeworld" to eod("Planet", "climate" to eod("Climate", "summary" to "arid"))
            )
        )
        val planet = result.get("homeworld") as EngineObjectData.Sync
        val climate = planet.get("climate") as EngineObjectData.Sync
        assertEquals("Planet", planet.type.name)
        assertEquals("Climate", climate.type.name)
        assertEquals("arid", climate.get("summary"))
    }

    @Test
    fun `a nested object is usable where an interface or union member is required`() {
        // A name-only placeholder has no interfaces and belongs to no union, which is what
        // EODBuilderWrapper validates against.
        val result = roundTrip(
            eod(
                "Character",
                "homeworld" to eod("Planet", "name" to "Tatooine"),
                "friends" to listOf(eod("Character", "name" to "Leia"))
            )
        )
        val homeworld = (result.get("homeworld") as EngineObjectData.Sync).type
        val friend = ((result.get("friends") as List<*>)[0] as EngineObjectData.Sync).type

        assertTrue(homeworld.interfaces.any { it.name == "Named" }, "nested type should carry its interfaces")
        val being = graphQLSchema.getType("Being") as graphql.schema.GraphQLUnionType
        assertTrue(being.isPossibleType(friend), "nested type should be a possible type of Being")
    }

    @Test
    fun `a list of objects keeps each element's type`() {
        val result = roundTrip(
            eod("Character", "friends" to listOf(eod("Character", "name" to "Leia"), eod("Character", "name" to "Han")))
        )
        val friends = result.get("friends") as List<*>
        assertEquals(2, friends.size)
        assertEquals("Character", (friends[0] as EngineObjectData.Sync).type.name)
        assertEquals("Leia", (friends[0] as EngineObjectData.Sync).get("name"))
        assertEquals("Han", (friends[1] as EngineObjectData.Sync).get("name"))
    }

    @Test
    fun `a nested list and a list holding null round-trip`() {
        val result = roundTrip(eod("Character", "friends" to listOf(listOf(1, 2), null, listOf<Any?>())))
        assertEquals(listOf(listOf(1, 2), null, emptyList<Any?>()), result.get("friends"))
    }

    @Test
    fun `a map-valued scalar stays a Map instead of becoming an object`() {
        // Regression: map values used to ship untagged, so the decoder rebuilt every JSON object as an
        // EngineObjectData — silently turning a custom-scalar payload into an object.
        val payload = mapOf("a" to 1, "b" to listOf("x"), "c" to mapOf("d" to true))
        val result = roundTrip(eod("Character", "metadata" to payload))
        assertEquals(payload, result.get("metadata"))
    }

    @Test
    fun `an unset selection stays unset and a null selection stays null`() {
        val result = roundTrip(eod("Character", "name" to null))
        assertNull(result.getOrNull("name"), "an explicit null survives as null")
        assertTrue(result.getSelections().contains("name"), "an explicit null stays a present selection")
        assertNull(result.getOrNull("homeworld"), "an unset selection is absent, not null-valued")
        assertTrue(!result.getSelections().contains("homeworld"), "an unset selection must not appear on the wire")
    }

    @Test
    fun `alias-keyed selections are preserved`() {
        // Values passed *into* a resolver may be keyed by alias rather than field name, so keys must
        // travel verbatim — the codec never maps them back through the schema.
        val result = roundTrip(eod("Character", "aliasedName" to "Luke"))
        assertEquals("Luke", result.get("aliasedName"))
    }

    @Test
    fun `a nested node reference is rejected rather than awaited`() {
        val character = eod("Character", "homeworld" to context.createNodeReference("Planet:1", type("Planet")))
        val ex = assertThrows<UnsupportedOperationException> { EngineObjectDataSerializer.serialize(character) }
        ex.message.shouldContain("nested NodeReference")
    }

    @Test
    fun `a node reference in an object payload is rejected on decode`() {
        // Only a field-value payload may carry a reference; an object payload has no context to rebuild
        // one, so a peer that sends one must be rejected rather than silently mishandled.
        val payload = EngineObjectDataSerializer.wrap(
            mapOf("o" to mapOf("t" to "Character", "f" to mapOf("homeworld" to mapOf("r" to mapOf("t" to "Planet", "id" to "Planet:1")))))
        )
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(payload, graphQLSchema, "Character")
        }
        ex.message.shouldContain("only a field-value payload may do")
    }

    @Test
    fun `a payload declaring a type this schema does not define is rejected`() {
        val payload = EngineObjectDataSerializer.wrap(mapOf("o" to mapOf("t" to "Wookiee", "f" to emptyMap<String, Any?>())))
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(payload, graphQLSchema, "Character")
        }
        ex.message.shouldContain("which this schema does not define")
    }

    @Test
    fun `a payload whose type disagrees with the receiver's expected type is rejected`() {
        // The receiver independently knows what this payload must be, so a mislabelled-but-known type
        // must not be silently accepted.
        val bytes = EngineObjectDataSerializer.serialize(eod("Planet", "name" to "Tatooine"))
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(bytes, graphQLSchema, "Character")
        }
        ex.message.shouldContain("declares type 'Planet' but this receiver expected 'Character'")
    }

    @Test
    fun `a non-object payload is rejected where an object is expected`() {
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(EngineObjectDataSerializer.wrap("just a string"), graphQLSchema, "Character")
        }
        ex.message.shouldContain("Expected an EngineObjectData payload")
    }

    @Test
    fun `empty payload bytes are rejected`() {
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(ByteArray(0), graphQLSchema, "Character")
        }
        ex.message.shouldContain("empty")
    }

    // --- Cross-build compatibility -------------------------------------------------------------
    //
    // The two processes must run compatible builds. These tests pin that a mismatch fails loudly in
    // *both* directions, rather than decoding into a wrong-but-plausible value. The pre-versioned
    // decoder is frozen below so "an old reader rejects a new payload" is actually exercised, not
    // just asserted.

    @Test
    fun `a pre-versioned payload is rejected by this reader with a build-mismatch error`() {
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(legacyEncoded(), graphQLSchema, "Character")
        }
        ex.message.shouldContain("incompatible build")
    }

    @Test
    fun `a versioned payload is rejected by the pre-versioned reader`() {
        val current = EngineObjectDataSerializer.serialize(eod("Character", "name" to "Luke"))
        // The frozen decoder read any JSON object as a field map, so only a non-object root can make it
        // fail; this is why the versioned root is an array. Assert the specific Jackson failure rather
        // than any exception, so this can't pass for an unrelated reason.
        assertThrows<MismatchedInputException> { legacyDecode(current) }
    }

    @Test
    fun `an unknown wire version is rejected`() {
        val future = objectMapper.writeValueAsBytes(listOf(99, mapOf("o" to mapOf("t" to "Character", "f" to emptyMap<String, Any?>()))))
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(future, graphQLSchema, "Character")
        }
        ex.message.shouldContain("Unsupported remote payload version 99")
    }

    @Test
    fun `a wire type name that is not an object type is rejected with the schema-mismatch error`() {
        // Regression: schema.getObjectType() *asserts* for a name that resolves to a non-object type,
        // so a graphql.AssertException escaped before the intended diagnostic could run. A type
        // changing kind between the two schemas is exactly what skew looks like.
        val payload = EngineObjectDataSerializer.wrap(mapOf("o" to mapOf("t" to "Named", "f" to emptyMap<String, Any?>())))
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(payload, graphQLSchema, "Character")
        }
        ex.message.shouldContain("does not define as an object type")
    }

    @Test
    fun `a map-valued scalar with a non-String key is rejected instead of being stringified`() {
        // Jackson would write a non-String key as its toString(), so it would arrive as a garbage
        // string with no error anywhere.
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.serialize(eod("Character", "metadata" to mapOf(1 to "a")))
        }
        ex.message.shouldContain("non-String key")
    }

    @Test
    fun `a scalar map whose own keys are envelope keys still decodes as a Map`() {
        // "s" bodies are opaque, so envelope keys inside one carry no meaning.
        val payload = mapOf("o" to 1, "r" to 2, "s" to 3, "t" to "x", "f" to listOf(1))
        val result = roundTrip(eod("Character", "metadata" to payload))
        assertEquals(payload, result.get("metadata"))
    }

    @Test
    fun `an enum encodes as its name rather than being rejected`() {
        // SyncEngineObjectDataFactory returns enums as-is for values built from OER slots, so one can
        // reach the codec. It decodes as a String, which EODBuilderWrapper.unwrapEnum accepts.
        val result = roundTrip(eod("Character", "name" to ProbeEnum.SECOND))
        assertEquals("SECOND", result.get("name"))
    }

    @Test
    fun `a payload nested past the depth cap is rejected on decode`() {
        // Jackson's own nesting limit is 1000, so the cap here is what rejects this.
        var nested: Any? = "leaf"
        repeat(200) { nested = listOf(nested) }
        val payload = EngineObjectDataSerializer.wrap(
            mapOf("o" to mapOf("t" to "Character", "f" to mapOf("friends" to nested)))
        )
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.deserialize(payload, graphQLSchema, "Character")
        }
        ex.message.shouldContain("nests deeper than")
    }

    @Test
    fun `a scalar map nested past the depth cap is rejected`() {
        var nested: Any = "leaf"
        repeat(200) { nested = mapOf("k" to nested) }
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.serialize(eod("Character", "metadata" to nested))
        }
        ex.message.shouldContain("nests deeper than")
    }

    @Test
    fun `a structure nested past the depth cap is rejected rather than overflowing the stack`() {
        // A StackOverflowError is an Error, so it bypasses every per-item isolation catch and takes
        // down the whole batch; the cap turns it into an ordinary isolated failure.
        var nested: Any? = "leaf"
        repeat(200) { nested = listOf(nested) }
        val ex = assertThrows<UnsupportedOperationException> {
            EngineObjectDataSerializer.serialize(eod("Character", "friends" to nested))
        }
        ex.message.shouldContain("nests deeper than")
    }

    /** A payload in the pre-versioned format: a bare field map, no type information. */
    private fun legacyEncoded(): ByteArray = objectMapper.writeValueAsBytes(mapOf("name" to "Luke"))

    /**
     * The pre-versioned decoder, frozen. It accepted *any* JSON object as a field map, which is
     * precisely why a versioned payload has to be structurally distinguishable from it.
     */
    private fun legacyDecode(jsonBytes: ByteArray): Map<*, *> = objectMapper.readValue(jsonBytes, Map::class.java)

    private enum class ProbeEnum { FIRST, SECOND }
}
