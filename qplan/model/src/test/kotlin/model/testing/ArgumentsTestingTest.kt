package model.testing

import kotlin.test.Test
import kotlin.test.assertSame
import model.fragmentFrom
import model.requireObjectField

class ArgumentsTestingTest {
    @Test
    fun `replacing arguments preserves inclusion conditions`() {
        val schema =
            TestWorld.fromSDL(
                """
                type Query {
                  value(arg: Int!): Int!
                }
                """.trimIndent(),
            ).schema
        val field = schema.requireObjectField("Query", "value")
        val fragment =
            schema.fragmentFrom(
                source =
                    """
                    fragment Generated on Query {
                      value(arg: 1) @include(if: ${'$'}condition)
                    }
                    """.trimIndent(),
                variableField = field,
            )
        val materializeSelection = fragment.materializeSelections.single()
        val selection = fragment.subselections.single()

        assertSame(
            materializeSelection.inclusionCondition,
            materializeSelection.withErrorArguments(setOf("arg")).inclusionCondition,
        )
        assertSame(
            selection.inclusionCondition,
            selection.withErrorArguments(setOf("arg")).inclusionCondition,
        )
    }
}
