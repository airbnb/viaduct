package semantics.contract

import kotlin.test.assertEquals
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.VariableBinding
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireField
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import org.junit.jupiter.api.Test

/**
 * Contract for nonempty object fragments with variables bound from resolver arguments.
 */
interface ObjectFragmentFromArgumentResolverContract :
    ResolverContract,
    NestedFromArgumentDemandResolverContract,
    PassiveFromArgumentDemandResolverContract,
    RecursiveListFromArgumentDemandResolverContract {
    @Test
    fun `resolves input selected with a fromArgument variable`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "consume(value: ${'$'}seed)"
                          result: "sum(consume)"
                        )
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value, ${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultField = world.schema.requireObjectField("Query", "result")
        val firstKey =
            ObjectEngineResult.GroundKey.of(
                resultField,
                mapOf("seed" to 7),
            )
        val secondKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 8))
        val resolution =
            resolveAndValidateObserved(
                world,
                """
                query Resolve(${'$'}first: Int!, ${'$'}second: Int!) {
                  first: result(seed: ${'$'}first)
                  second: result(seed: ${'$'}second)
                }
                """.trimIndent(),
                variables = mapOf("first" to 7, "second" to 8),
                resolverObserver = applicationArguments,
            )
        val resolved = resolution.result

        assertEquals(14, resolved.getCell(firstKey).get())
        assertEquals(16, resolved.getCell(secondKey).get())
        applicationArguments.assertArguments(
            world.schema.requireField("Query", "consume"),
            mapOf("value" to 7),
            mapOf("value" to 8),
        )
        val resolver = world.resolverRegistry.resolver(resultField)
        listOf(
            firstKey to 7,
            secondKey to 8,
        ).forEach { (groundKey, expectedValue) ->
            val path = listOf(groundKey)
            val variableInstances =
                resolver
                    .instantiatedVariableDefinitions(
                        ResolverOccurrenceId.at(resolved, path),
                    )
                    .map { definition -> definition.variable }
            variableInstances.forEach { boundVariable ->
                assertEquals(
                    VariableBinding.of(expectedValue),
                    resolution.operation.variableBindings.getBinding(
                        requireNotNull(boundVariable.instanceId),
                    ),
                )
            }
        }
    }

    @Test
    fun `resolves a fromArgument variable whose name differs from its argument`() {
        val resultFragment =
            """
            fragment Result on Query {
              consume(value: ${'$'}argumentValue)
            }
            """.trimIndent()
        // The compact DSL reserves $argname for same-named argument variables.
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      result(value: Int!): Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    val consume = schema.requireObjectField("Query", "consume")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.selectionValues().getValue("consume")
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "argumentValue") to
                            schema.fromArgument(result, "value"),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("value" to 7),
            )
        val resolved =
            resolveAndValidate(world, "query { result(value: 7) }")

        assertEquals(7, resolved.getCell(resultKey).get())
    }

    @Test
    fun `resolves a fromArgument variable through a nested input object`() {
        val resultFragment =
            """
            fragment Result on Query {
              consume(value: ${'$'}nestedValue)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    input Input {
                      value: Int!
                    }

                    type Query {
                      result(input: Input!): Int!
                      consume(value: Int!): Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    val consume = schema.requireObjectField("Query", "consume")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.selectionValues().getValue("consume")
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value") as Int
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "nestedValue") to
                            schema.fromArgument(result, listOf("input", "value")),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("input" to mapOf("value" to 2)),
            )

        val resolved = resolveAndValidate(world, "query { result(input: {value: 2}) }")

        assertEquals(2, resolved.getCell(resultKey).get())
    }

    @Test
    fun `binds null when a fromArgument path traverses a null input object`() {
        val resultFragment =
            """
            fragment Result on Query {
              consume(value: ${'$'}nestedValue)
            }
            """.trimIndent()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    input Input {
                      value: Int! = 2
                    }

                    type Query {
                      result(input: Input): Int!
                      consume(value: Int): Int
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    val consume = schema.requireObjectField("Query", "consume")
                    mapOf(
                        result to
                            fieldResolverOf(schema.fragmentFrom(resultFragment)) { input, _ ->
                                input.selectionValues().getValue("consume") ?: 7
                            },
                        consume to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.requireObjectField("Query", "result")
                    mapOf(
                        Arguments.Variable.of(result, "nestedValue") to
                            schema.fromArgument(result, listOf("input", "value")),
                    )
                },
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                mapOf("input" to null),
            )

        val resolved = resolveAndValidate(world, "query { result(input: null) }")

        assertEquals(7, resolved.getCell(resultKey).get())
    }

    @Test
    fun `resolves a transitive chain of fromArgument variables`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      one(seed: Int!): Int!
                        @resolver(of: "two(value: ${'$'}seed)", result: "sum(two)")
                      two(value: Int!): Int!
                        @resolver(of: "three(value: ${'$'}value)", result: "sum(three)")
                      three(value: Int!): Int!
                        @resolver(result: "sumplus1(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val oneKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "one"),
                mapOf("seed" to 7),
            )
        val resolved = resolveAndValidate(world, "query { one(seed: 7) }")

        assertEquals(8, resolved.getCell(oneKey).get())
    }
}
