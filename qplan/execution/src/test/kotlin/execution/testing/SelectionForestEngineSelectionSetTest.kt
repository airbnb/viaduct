package execution.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.emptyFragmentOf
import model.fragmentFrom
import model.lowering.SourceSchemaAdapter
import model.testing.TestWorld
import viaduct.engine.api.EngineSelection
import viaduct.engine.api.mocks.createSchema

class SelectionForestEngineSelectionSetTest {
    @Test
    fun `preserves empty demand`() {
        val fixture = Fixture(CONCRETE_SCHEMA)
        val fragment = fixture.world.schema.emptyFragmentOf("Foo")

        val selections =
            fragment.subselections.toEngineSelectionSet(
                type = fragment.nominalType,
                schema = fixture.engineSchema,
                sourceSchema = fixture.sourceSchema,
            )

        assertEquals("Foo", selections.type)
        assertTrue(selections.isEmpty())
        assertTrue(selections.isTransitivelyEmpty())
    }

    @Test
    fun `preserves concrete fields nested demand and resolved arguments`() {
        val fixture = Fixture(CONCRETE_SCHEMA)
        val fragment =
            fixture.world.schemas.fragmentFrom(
                """
                fragment _ on Foo {
                  calculated(scale: 3, labels: ["a", "b"])
                  child { value }
                }
                """.trimIndent(),
            )

        val selections =
            fragment.subselections.toEngineSelectionSet(
                type = fragment.nominalType,
                schema = fixture.engineSchema,
                sourceSchema = fixture.sourceSchema,
            )

        assertEquals("Foo", selections.type)
        assertTrue(selections.containsField("Foo", "calculated"))
        assertEquals(
            mapOf("scale" to 3, "labels" to listOf("a", "b")),
            selections.argumentsOfSelection("Foo", "calculated"),
        )
        assertTrue(
            selections
                .selectionSetForField("Foo", "child")
                .containsField("Child", "value"),
        )
    }

    @Test
    fun `canonical concrete fragments preserve abstract applicability`() {
        val fixture = Fixture(ABSTRACT_SCHEMA)
        val fragment =
            fixture.world.schemas.fragmentFrom(
                """
                fragment _ on Item {
                  common
                  ... on Alpha { alpha }
                  ... on Beta { beta }
                }
                """.trimIndent(),
            )

        val selections =
            fragment.subselections.toEngineSelectionSet(
                type = fragment.nominalType,
                schema = fixture.engineSchema,
                sourceSchema = fixture.sourceSchema,
            )

        assertEquals(
            setOf(
                EngineSelection("Alpha", "alpha", "alpha"),
                EngineSelection("Alpha", "common", "common"),
            ),
            selections.selectionSetForType("Alpha").selections().toSet(),
        )
        assertEquals(
            setOf(
                EngineSelection("Beta", "beta", "beta"),
                EngineSelection("Beta", "common", "common"),
            ),
            selections.selectionSetForType("Beta").selections().toSet(),
        )
        assertFalse(selections.containsField("Alpha", "beta"))
        assertFalse(selections.containsField("Beta", "alpha"))
        assertTrue(selections.requestsType("Alpha"))
        assertTrue(selections.requestsType("Beta"))
    }

    @Test
    fun `restores lowered typename demand to the source meta field`() {
        val fixture = Fixture(ABSTRACT_SCHEMA)
        val fragment =
            fixture.world.schemas.fragmentFrom(
                """
                fragment _ on Item {
                  __typename
                }
                """.trimIndent(),
            )

        val selections =
            fragment.subselections.toEngineSelectionSet(
                type = fragment.nominalType,
                schema = fixture.engineSchema,
                sourceSchema = fixture.sourceSchema,
            )

        assertTrue(selections.containsField("Alpha", "__typename"))
        assertTrue(selections.containsField("Beta", "__typename"))
    }

    @Test
    fun `preserves nested Node field demand at source coordinates`() {
        val fixture = Fixture(NODE_SCHEMA, NODE_ENGINE_SCHEMA)
        val fragment =
            fixture.world.schemas.fragmentFrom(
                "fragment _ on User { friend { name } }",
            )

        val selections =
            fragment.subselections.toEngineSelectionSet(
                type = fragment.nominalType,
                schema = fixture.engineSchema,
                sourceSchema = fixture.sourceSchema,
            )

        assertTrue(selections.containsField("User", "friend"))
        assertTrue(
            selections
                .selectionSetForField("User", "friend")
                .containsField("User", "name"),
        )
    }

    private class Fixture(
        schemaSDL: String,
        engineSchemaSDL: String = schemaSDL,
    ) {
        val world = TestWorld.fromSDL(schemaSDL)
        val sourceSchema = SourceSchemaAdapter(world.schema)
        val engineSchema = createSchema(engineSchemaSDL.replaceFirst("type Query", "extend type Query"))
    }

    private companion object {
        val CONCRETE_SCHEMA =
            """
            type Query { foo: Foo }
            type Foo {
              calculated(scale: Int!, labels: [String!]!): Int
              child: Child
            }
            type Child { value: Int }
            """.trimIndent()

        val ABSTRACT_SCHEMA =
            """
            type Query { item: Item }
            interface Item { common: Int }
            type Alpha implements Item { common: Int, alpha: Int }
            type Beta implements Item { common: Int, beta: Int }
            """.trimIndent()

        val NODE_SCHEMA =
            """
            interface Node { id: ID! }
            type Query { user: User }
            type User implements Node { id: ID!, name: String, friend: User }
            """.trimIndent()

        val NODE_ENGINE_SCHEMA =
            """
            type Query { user: User }
            type User implements Node { id: ID!, name: String, friend: User }
            """.trimIndent()
    }
}
