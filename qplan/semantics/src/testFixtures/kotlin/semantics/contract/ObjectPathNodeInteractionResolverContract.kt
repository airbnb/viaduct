package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import model.requireObjectField
import model.testing.TestWorld
import semantics.shared.ResolverInvocationObservation

interface ObjectPathNodeInteractionResolverContract : ResolverContract {
    @Test
    fun `late provider path deepens an already published node reference`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {profile: {id: "profile"}})
                      source: Int! @resolver(result: 7)
                      driver: Int!
                        @resolver(
                          of: "source item { trigger(value: ${'$'}sourceValue) }"
                          pathVars: [{name: "sourceValue", path: ["source"]}]
                          result: "sum(item.trigger)"
                        )
                    }

                    type Item {
                      profile: Profile!
                      trigger(value: Int!): Int!
                        @resolver(
                          of: "profile { details { value } } consume(value: ${'$'}detailValue)"
                          pathVars: [
                            {name: "detailValue", path: ["profile", "details", "value"]}
                          ]
                          result: "sum(consume)"
                        )
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Profile implements Node
                      @nodeResolver(
                        result: [
                          {
                            id: "profile"
                            result: {details: {value: 11}}
                          }
                        ]
                      ) {
                      id: ID!
                      details: Details!
                    }

                    type Details {
                      value: Int!
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
                    profile { id }
                  }
                  driver
                }
                """.trimIndent(),
            )

        assertEquals(
            11,
            resolved.getCell(world.schema.contractKey("Query", "driver")).get(),
        )
    }

    @Test
    fun `retains potential demand through a passive node reference`() {
        var triggerInputKeys: Set<String>? = null
        val invocationObserver = object : ResolverApplicationArguments() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val input = observation.input
                if (
                    field.containingDef.name == "Item" &&
                    field.name == "trigger"
                ) {
                    triggerInputKeys = input.selectionValues().keys
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(result: {profile: {id: "profile"}})
                      source: Int! @resolver(result: 7)
                      driver: Profile!
                        @resolver(
                          of: "source item { trigger(value: ${'$'}sourceValue) }"
                          pathVars: [{name: "sourceValue", path: ["source"]}]
                          result: {id: "driver"}
                        )
                    }

                    type Item {
                      profile: Profile!
                      trigger(value: Int!): Int!
                        @resolver(
                          of: "account: profile { details { related { __typename } } }"
                          result: 7
                        )
                    }

                    type Profile implements Node
                      @nodeResolver(
                        result: [
                          {
                            id: "profile"
                            result: {details: {related: {id: "related"}}}
                          }
                          {id: "related", result: {}}
                          {id: "driver", result: {}}
                        ]
                      ) {
                      id: ID!
                      details: Details!
                    }

                    type Details {
                      related: Profile!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        resolveAndValidate(
            testWorld,
            """
                    query {
                      item {
                        profile {
                          details { __typename }
                        }
                      }
                      driver { id }
                    }
            """.trimIndent(),
            resolverObserver = invocationObserver,
        )

        invocationObserver.assertApplicationCount(
            world.schema.requireObjectField("Query", "driver"),
            1,
        )
        assertEquals(setOf("account"), triggerInputKeys)
    }
}
