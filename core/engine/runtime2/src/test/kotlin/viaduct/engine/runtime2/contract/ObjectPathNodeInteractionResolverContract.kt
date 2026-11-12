package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

interface ObjectPathNodeInteractionResolverContract : ResolverContract {
    @Test
    fun `late provider path deepens an already published node reference`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(script: "{'profile': {'__typename': 'Profile', 'id': 'profile'}}")
                      source: Int! @resolver(script: "7")
                      driver: Int!
                        @resolver(
                          of: "source item { trigger(value: ${'$'}sourceValue) }"
                          pathVars: [{name: "sourceValue", path: ["source"]}]
                          script: "(objectValue.item == null ? 0 : objectValue.item.trigger == null ? 0 : objectValue.item.trigger)"
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
                          script: "(objectValue.consume == null ? 0 : objectValue.consume)"
                        )
                      consume(value: Int!): Int!
                        @resolver(script: "value")
                    }

                    type Profile implements Node
                      @nodeResolver(ids: ["profile"], script: "{'details': {'value': 11}}") {
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
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
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
                schemaSDL =
                    """
                    extend type Query {
                      item: Item!
                        @resolver(script: "{'profile': {'__typename': 'Profile', 'id': 'profile'}}")
                      source: Int! @resolver(script: "7")
                      driver: Profile!
                        @resolver(
                          of: "source item { trigger(value: ${'$'}sourceValue) }"
                          pathVars: [{name: "sourceValue", path: ["source"]}]
                          script: "{'__typename': 'Profile', 'id': 'driver'}"
                        )
                    }

                    type Item {
                      profile: Profile!
                      trigger(value: Int!): Int!
                        @resolver(
                          of: "account: profile { details { related { __typename } } }"
                          script: "7"
                        )
                    }

                    type Profile implements Node
                      @nodeResolver(ids: ["profile", "related", "driver"], script: "id == 'profile' ? {'details': {'related': {'id': 'related'}}} : id == 'related' ? {:} : {:}") {
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
            resolutionObserver = invocationObserver,
        )

        invocationObserver.assertApplicationCount(
            world.schema.requireObjectField("Query", "driver"),
            1,
        )
        assertEquals(setOf("account"), triggerInputKeys)
    }
}
