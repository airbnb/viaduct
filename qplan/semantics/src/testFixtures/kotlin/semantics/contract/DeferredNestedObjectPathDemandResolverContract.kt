package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import model.requireObjectField
import model.testing.TestWorld

interface DeferredNestedObjectPathDemandResolverContract : ResolverContract {
    @Test
    fun `retains potential demand beyond a deferred nested resolver`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {})
                      source: Int! @resolver(result: 7)
                      driver: Int!
                        @resolver(
                          of: "source item { result(value: ${'$'}sourceValue) }"
                          pathVars: [{name: "sourceValue", path: ["source"]}]
                          result: "sum(item.result)"
                        )
                    }

                    type Item {
                      step: Item! @resolver(result: {passive: 7})
                      passive: Int!
                      result(value: Int!): Int!
                        @resolver(
                          of: "step { passive }"
                          result: "sum(step.passive)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                testWorld,
                """
                query {
                  item {
                    step { __typename }
                  }
                  driver
                }
                """.trimIndent(),
                resolverObserver = applicationArguments,
            )

        assertEquals(
            7,
            resolved
                .getCell(
                    world.schema.contractKey("Query", "driver"),
                ).get(),
        )
        applicationArguments.assertArguments(
            world.schema.requireObjectField("Item", "result"),
            mapOf("value" to 7),
        )
    }
}
