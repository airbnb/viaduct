package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.model.testing.TestWorld

/** Contract for consuming an object-fragment provider binding from a Query fragment. */
interface QueryFragmentFromObjectPathResolverContract : ResolverContract {
    @Test
    fun `object fragment provider binding can be used by the query fragment`() {
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      provider: Int! @resolver(result: 7)
                      source(value: Int!): Int! @resolver(result: "value(${'$'}value)")
                      consumer: Int!
                        @resolver(
                          of: "provided: provider"
                          qf: "querySide: source(value: ${'$'}providedValue)"
                          pathVars: [{name: "providedValue", path: ["provided"]}]
                          result: "value(query:querySide)"
                        )
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val consumerKey = world.schema.contractKey("Query", "consumer")

        val resolved = resolveAndValidate(testWorld, "query { consumer }")

        assertEquals(7, resolved.getCell(consumerKey).get())
    }
}
