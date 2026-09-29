package semantics.contract

import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import model.testing.TestWorld

/**
 * Contract translation of the production required-selection deadlock.
 *
 * The production node executor re-enters Query.a while Foo is being selectively
 * materialized. Qplan has no callback-shaped node executor, so this keeps the
 * mixed FromArgument/FromObjectField dependency topology and asserts that the
 * qplan registry rejects the cycle before resolution starts.
 */
interface ProductionDeadlockResolverContract : ResolverContract {
    @org.junit.jupiter.api.Test
    fun `mixed argument and object path node demand is rejected as a cycle`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                TestWorld.fromDSL(
                    selectiveResolvers = selectiveResolvers,
                    schemaSDL =
                        """
                    extend type Query {
                      foo: Foo!
                        @resolver(result: {id: "foo"})
                      a(value: Int!): Int!
                        @resolver(
                          of: "source: foo { y(value: ${'$'}value) } target: foo { y(value: ${'$'}fromY) }"
                          pathVars: [{name: "fromY", path: ["source", "y"]}]
                          result: "sum(source.y, target.y)"
                        )
                    }

                    type Foo implements Node
                      @nodeResolver(result: [{id: "foo", result: {}}]) {
                      id: ID!
                      y(value: Int!): Int!
                        @resolver(result: "value(${'$'}value)")
                    }
                        """.trimIndent(),
                )
            }

        assertTrue(
            error.message!!.contains("contains a cycle foo -> foo"),
        )
    }
}
