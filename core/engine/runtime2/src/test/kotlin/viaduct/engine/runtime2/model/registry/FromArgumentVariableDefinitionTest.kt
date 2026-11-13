package viaduct.engine.runtime2.model.registry

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.inputType
import viaduct.engine.runtime2.model.requireArg
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class FromArgumentVariableDefinitionTest {
    private val world =
        TestWorld.fromSDL(
            """
            input Input {
              nested: Nested
            }

            input Nested {
              value: Int! = 2
            }

            type Query {
              result(input: Input, inputs: [Input], value: Int): Int
            }
            """.trimIndent(),
        )
    private val schema = world.schema
    private val result = schema.requireObjectField("Query", "result")
    private val input = schema.requireType("Input") as ViaductSchema.Input
    private val nested = schema.requireType("Nested") as ViaductSchema.Input
    private val inputPath =
        listOf(
            input.requireField("nested"),
            nested.requireField("value"),
        )

    @Test
    fun `reads an omitted nullable scalar argument as null`() {
        val definition = VariableDefinition.FromArgument.of(result.requireArg("value"))

        assertEquals(null, definition.read(Arguments.Resolved.of(result, emptyMap())))
    }

    @Test
    fun `short-circuits a nested path when the input argument is omitted`() {
        val definition = VariableDefinition.FromArgument.of(result.requireArg("input"), inputPath)

        assertEquals(null, definition.read(Arguments.Resolved.of(result, emptyMap())))
    }

    @Test
    fun `reads a nested input path and short-circuits null traversal`() {
        val definition =
            VariableDefinition.FromArgument.of(
                argument = result.requireArg("input"),
                inputPath = inputPath,
            )

        assertEquals(
            3,
            definition.read(
                Arguments.Resolved.of(
                    result,
                    mapOf("input" to mapOf("nested" to mapOf("value" to 3))),
                ),
            ),
        )
        assertEquals(
            null,
            definition.read(
                Arguments.Resolved.of(
                    result,
                    mapOf("input" to mapOf("nested" to null)),
                ),
            ),
        )
        assertEquals(
            null,
            definition.read(
                Arguments.Resolved.of(
                    result,
                    mapOf("input" to null),
                ),
            ),
        )
    }

    @Test
    fun `rejects traversal through a list argument`() {
        val argument = result.requireArg("inputs")
        assert(argument.inputType.isList)

        assertFailsWith<IllegalArgumentException> {
            VariableDefinition.FromArgument.of(
                argument = argument,
                inputPath = listOf(input.requireField("nested")),
            )
        }
    }
}
