package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import model.ObjectEngineResult
import model.requireObjectField
import model.testing.TestWorld

interface PassiveFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `closes potential demand before descending through a passive object`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      container: Container!
                        @resolver(result: {bridge: {id: 1}})
                      result(value: Int!): Int!
                        @resolver(
                          of: "container { trigger(value: ${'$'}value) }"
                          result: "sum(container.trigger)"
                        )
                    }

                    type Container {
                      bridge: Bridge!
                      trigger(value: Int!): Int!
                        @resolver(
                          of: "bridge { load { __typename } }"
                          result: 1
                        )
                    }

                    type Bridge {
                      id: Int!
                      load: Entity!
                        @resolver(of: "id", result: {name: 2})
                    }

                    type Entity {
                      name: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                """
                query {
                  container {
                    bridge {
                      load { name }
                    }
                  }
                  result(value: 7)
                }
                """.trimIndent(),
                resolverObserver = applicationArguments,
            )

        assertEquals(
            1,
            resolved.getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "result"),
                    mapOf("value" to 7),
                ),
            ).get(),
        )
        applicationArguments.assertApplicationCount(
            world.schema.requireObjectField("Bridge", "load"),
            1,
        )
        applicationArguments.assertApplicationCount(
            world.schema.requireObjectField("Container", "trigger"),
            1,
        )
        applicationArguments.assertArguments(
            world.schema.requireObjectField("Container", "trigger"),
            mapOf("value" to 7),
        )
    }
}
