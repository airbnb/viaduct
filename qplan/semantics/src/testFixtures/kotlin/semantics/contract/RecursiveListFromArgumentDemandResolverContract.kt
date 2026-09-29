package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import model.ObjectEngineResult
import model.requireObjectField
import model.testing.TestWorld

interface RecursiveListFromArgumentDemandResolverContract : ResolverContract {
    @Test
    fun `deepens an already launched recursive list with typename demand`() {
        val applicationArguments = ResolverApplicationArguments()
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {common: 1})
                      result(value: Int!): Int!
                        @resolver(
                          of: "item { consume(value: ${'$'}value) }"
                          result: "sum(item.consume)"
                        )
                    }

                    type Item {
                      common: Int!
                      children: [Item!]!
                        @resolver(
                          of: "common"
                          result: [{common: 2}, {common: 3}]
                        )
                      consume(value: Int!): Int!
                        @resolver(
                          of: "children { __typename }"
                          result: 1
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(
                world,
                """
                query {
                  item {
                    children { common }
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
            world.schema.requireObjectField("Item", "children"),
            1,
        )
        applicationArguments.assertApplicationCount(
            world.schema.requireObjectField("Item", "consume"),
            1,
        )
        applicationArguments.assertArguments(
            world.schema.requireObjectField("Item", "consume"),
            mapOf("value" to 7),
        )
    }
}
