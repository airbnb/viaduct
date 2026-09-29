package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import model.ObjectEngineResult
import model.requireObjectField
import model.testing.TestWorld

interface NestedFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `retains passive demand below an ungrounded nested resolver key`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      holder: Item! @resolver(result: {passive: 7})
                      result(value: Int!): Int!
                        @resolver(
                          of: "holder { consume(value: ${'$'}value) }"
                          result: "sum(holder.consume)"
                        )
                    }

                    type Item {
                      consume(value: Int!): Int!
                        @resolver(of: "passive", result: "sum(passive)")
                      passive: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                """
                query {
                  holder { __typename }
                  result(value: 7)
                }
                """.trimIndent(),
                resolverObserver = applicationArguments,
            )

        assertEquals(
            7,
            resolved
                .getCell(
                    ObjectEngineResult.GroundKey.of(
                        world.schema.requireObjectField("Query", "result"),
                        mapOf("value" to 7),
                    ),
                ).get(),
        )
        applicationArguments.assertApplications(
            mapOf(
                world.schema.requireObjectField("Query", "holder") to listOf(emptyMap()),
                world.schema.requireObjectField("Query", "result") to
                    listOf(mapOf("value" to 7)),
                world.schema.requireObjectField("Item", "consume") to
                    listOf(mapOf("value" to 7)),
            ),
        )
    }
}
