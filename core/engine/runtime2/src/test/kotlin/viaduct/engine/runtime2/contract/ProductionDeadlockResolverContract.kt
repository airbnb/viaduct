package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

/** Runtime2 has no callback-shaped node executor re-entering Query.a, so this path is acyclic. */
interface ProductionDeadlockResolverContract : ResolverContract {
    @Test
    @Timeout(5)
    fun `resolves mixed argument and object path node demand within a shared branch`() {
        val testWorld =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      foo: Foo!
                        @resolver(script: "{'__typename': 'Foo', 'id': 'foo'}")
                      a(value: Int!): Int!
                        @resolver(
                          of: "source: foo { y(value: ${'$'}value) } target: foo { y(value: ${'$'}fromY) }"
                          pathVars: [{name: "fromY", path: ["source", "y"]}]
                          script: "(objectValue.source == null ? 0 : objectValue.source.y == null ? 0 : objectValue.source.y) + (objectValue.target == null ? 0 : objectValue.target.y == null ? 0 : objectValue.target.y)"
                        )
                    }

                    type Foo implements Node
                      @nodeResolver(ids: ["foo"], script: "{:}") {
                      id: ID!
                      y(value: Int!): Int!
                        @resolver(script: "value")
                    }
                    """.trimIndent(),
            )

        val resolved = resolveAndValidate(testWorld, "query { a(value: 5) }")
        val key = ObjectEngineResult.GroundKey.of(
            testWorld.schema.requireObjectField("Query", "a"),
            mapOf("value" to 5),
        )

        assertEquals(10, resolved.getCell(key).get())
    }
}
