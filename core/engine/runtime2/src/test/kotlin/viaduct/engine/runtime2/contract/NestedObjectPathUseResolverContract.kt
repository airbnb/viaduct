package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

interface NestedObjectPathUseResolverContract : ResolverContract {
    @Test
    fun `waits for a provider value before expanding a nested variable use`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source holder { consume(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(holder.consume)"
                        )
                      source: Int! @resolver(of: "delay", result: "sum(delay)")
                      holder: Holder! @resolver(result: {})
                      delay: Int! @resolver(result: 7)
                    }

                    type Holder {
                      consume(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey =
            ObjectEngineResult.GroundKey.of(
                world.schema.requireObjectField("Query", "result"),
                emptyMap(),
            )

        val resolved = resolveAndValidate(testWorld, "query { result }")

        assertEquals(7, resolved.getCell(resultKey).get())
    }
}
