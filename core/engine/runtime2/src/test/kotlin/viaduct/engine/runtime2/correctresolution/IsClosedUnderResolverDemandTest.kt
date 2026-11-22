package viaduct.engine.runtime2.correctresolution

import kotlin.test.Test
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.engineResultOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

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
            TestWorld.fromDSL(
                """
                extend type Query { parent: Parent! @resolver(script: "null") }
                type Parent {
                  child: Child!
                  result(seed: Int!): Int! @resolver(of: "child { consume(value: ${'$'}seed) }", script: "14")
                }
                type Child { consume(value: Int!): Int! @resolver(script: "14") }
                """.trimIndent(),
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
