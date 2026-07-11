package viaduct.remote

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.mocks.createEngineSelectionSet
import viaduct.engine.api.mocks.createEngineSelectionSetFactory
import viaduct.engine.api.select.SelectionsParser

/**
 * Unit tests for the selection-set wire form used by the remote-resolver NETWORK path.
 *
 * A field's sub-selection set can't be shipped as a per-JVM registry handle across processes, so
 * [RemoteFieldProxyExecutor] serializes it as `{type, fragment-document, variables}` and
 * [RemoteResolverServiceImpl] reconstructs it against the remote's own schema via the context's
 * [EngineSelectionSet.Factory][viaduct.engine.api.EngineSelectionSet.Factory]. This verifies that
 * triple round-trips to an equivalent selection set (including aliases, inline fragments, and
 * argument variables) — the same factory the production reconstruction uses.
 */
class RemoteSelectionSetWireTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { test: String }
        type Character {
            id: ID!
            name: String
            birthYear: String
            homeworld: Planet
            friends(first: Int): [Character]
        }
        type Planet {
            id: ID!
            name: String
        }
        """.trimIndent()
    )
    private val factory = createEngineSelectionSetFactory(schema)

    // Serializes a selection set to the wire form (type + fragment document + variables) and rebuilds
    // it, exactly as RemoteFieldProxyExecutor → RemoteResolverServiceImpl do over the network.
    private fun assertRoundTrips(
        type: String,
        selections: String,
        variables: Map<String, Any?> = emptyMap()
    ) {
        val original = createEngineSelectionSet(SelectionsParser.parse(type, selections), schema, variables)
        val reconstructed = factory.engineSelectionSet(original.type, original.document, original.variables)
        reconstructed.printAsFieldSet() shouldBe original.printAsFieldSet()
    }

    @Test
    fun `plain fields round-trip`() = assertRoundTrips("Character", "name birthYear")

    @Test
    fun `nested object selection round-trips`() = assertRoundTrips("Character", "name homeworld { name }")

    @Test
    fun `aliases round-trip`() = assertRoundTrips("Character", "who: name born: birthYear")

    @Test
    fun `inline fragment round-trips`() = assertRoundTrips("Character", "name ... on Character { birthYear }")

    @Test
    fun `argument variables round-trip`() = assertRoundTrips("Character", "friends(first: \$n) { name }", mapOf("n" to 3))
}
