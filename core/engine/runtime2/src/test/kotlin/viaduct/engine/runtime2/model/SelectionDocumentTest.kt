package viaduct.engine.runtime2.model

import graphql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.schema.fragmentFromDocument

class SelectionDocumentTest {
    @Test
    fun `active named fragment spread survives a statically excluded duplicate`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  value: Int!
                }
                """.trimIndent(),
            )

        val fragment =
            world.schemas.fragmentFromDocument(
                Parser.parse(
                    """
                    fragment Main on Query {
                      ...Value @include(if: false)
                      ... on Query { ...Value }
                    }

                    fragment Value on Query {
                      __typename @include(if: true)
                    }
                    """.trimIndent(),
                ),
            )

        val selection = fragment.subselections.merge(world.schema.requireQueryTypeDef()).single()
        assertEquals("V_A_typename", selection.key.field.name)
        assertSame(InclusionCondition.Always, selection.inclusionCondition)
        assertTrue(
            fragment.materializeSelections.all { materializeSelection ->
                materializeSelection.responseKey == "__typename"
            },
        )
    }

    @Test
    fun `accepts nested named fragments at the model fixture boundary`() {
        val world =
            TestWorld.fromSDL(
                """
                type Query {
                  value: Int!
                }
                """.trimIndent(),
            )

        val fragment =
            world.schemas.fragmentFromDocument(
                Parser.parse(
                    """
                    fragment Value on Query {
                      renamed: value
                    }
                    fragment Outer on Query {
                      ...Value
                    }
                    fragment Main on Query {
                      ...Outer
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("value", fragment.subselections.single().key.field.name)
        assertEquals("renamed", fragment.materializeSelections.single().responseKey)
    }

    @Test
    fun `named fragment lowering preserves generic field directives`() {
        val world =
            TestWorld.fromSDL(
                """
                directive @consumerPolicy on FIELD

                type Query {
                  value: Int!
                }
                """.trimIndent(),
            )

        val fragment =
            world.schemas.fragmentFromDocument(
                Parser.parse(
                    """
                    fragment Directed on Query {
                      renamed: value @consumerPolicy
                    }
                    fragment Main on Query {
                      ...Directed
                    }
                    """.trimIndent(),
                ),
            )

        val selection = fragment.materializeSelections.single()
        assertEquals("renamed", selection.responseKey)
        assertTrue(checkNotNull(selection.fieldDirectives).hasDirective("consumerPolicy"))
    }
}
