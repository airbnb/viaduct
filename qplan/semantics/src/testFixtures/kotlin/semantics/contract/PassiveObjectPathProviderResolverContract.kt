package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import model.ObjectEngineResult
import model.requireObjectField
import model.testing.TestWorld

interface PassiveObjectPathProviderResolverContract : ResolverContract {
    @Test
    fun `installs a resolver promise below a passive provider field`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {provider: {}})
                    }

                    type Item {
                      result: Int!
                        @resolver(
                          of: "provider { value } consume(value: ${'$'}value)"
                          pathVars: [{name: "value", path: ["provider", "value"]}]
                          result: "sum(consume)"
                        )
                      provider: Provider!
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }

                    type Provider {
                      value: Int! @resolver(result: 11)
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions

        val resolved =
            resolveAndValidate(world, "query { item { result } }")
        val item =
            resolved.getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Query", "item"),
                    emptyMap(),
                ),
            ).get() as ObjectEngineResult

        assertEquals(
            11,
            item.getCell(
                ObjectEngineResult.GroundKey.of(
                    world.schema.requireObjectField("Item", "result"),
                    emptyMap(),
                ),
            ).get(),
        )
    }
}
