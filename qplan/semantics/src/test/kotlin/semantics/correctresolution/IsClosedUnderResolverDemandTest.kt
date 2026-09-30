package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertTrue
import model.Arguments
import model.ObjectEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.engineResultOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import semantics.shared.SharedOperationContext

class IsClosedUnderResolverDemandTest {
    @Test
    fun `non-node object does not require an id field`() {
        val worldFixture = TestWorld.fromSDL(SCHEMA_SDL)
        val world = worldFixture.assumptions
        val result =
            world.engineResultOf("Profile") {
                "name" resolvesTo "Ada"
            }

        assertTrue(result.isClosedUnderResolverDemand(SharedOperationContext.create(world)))
    }

    @Test
    fun `bound variables are substituted in nested resolver demand`() {
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL =
                    """
                    type Child {
                      consume(value: Int!): Int!
                    }

                    type Parent {
                      child: Child!
                      result(seed: Int!): Int!
                    }

                    type Query {
                      parent: Parent!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Parent", "result")
                    val consume = schema.loweredSchema.requireObjectField("Child", "consume")
                    val parent = schema.loweredSchema.requireObjectField("Query", "parent")
                    mapOf(
                        result to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    """
                                    fragment ignored on Parent {
                                      child {
                                        consume(value: ${'$'}seed)
                                      }
                                    }
                                    """.trimIndent(),
                                ),
                            ) { _, _ -> 14 },
                        consume to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Child"),
                            ) { _, _ -> 14 },
                        parent to
                            fieldResolverOf(
                                schema.loweredSchema.emptyFragmentOf("Query"),
                            ) { _, _ -> null },
                    )
                },
                variableProviders = { schema ->
                    val result = schema.loweredSchema.requireObjectField("Parent", "result")
                    mapOf(
                        Arguments.Variable.of(result, "seed") to
                            schema.loweredSchema.fromArgument(result, "seed"),
                    )
                },
            )
        val world = testWorld.assumptions
        val operation = SharedOperationContext.create(world)
        val resultField = testWorld.schema.requireObjectField("Parent", "result")
        val resultKey = ObjectEngineResult.GroundKey.of(resultField, mapOf("seed" to 7))
        val result =
            world.engineResultOf("Parent") {
                "child" resolvesTo
                    engineResultOf("Child") {
                        field("consume", "value" to 7) resolvesTo 14
                    }
                field("result", "seed" to 7) resolvesTo 14
            }
        val variable = Arguments.Variable.of(resultField, "seed")
        val instantiated =
            variable.instantiate(ResolverOccurrenceId.at(result, listOf(resultKey)))
        val variableId = requireNotNull(instantiated.instanceId)
        operation.variableBindings.declareBinding(variableId)
        operation.variableBindings.completeBinding(variableId, 7)

        assertTrue(result.isClosedUnderResolverDemand(operation))
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Profile {
              name: String!
            }

            type Query {
              profile: Profile!
            }
            """.trimIndent()
    }
}
