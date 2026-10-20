package viaduct.engine.runtime2.model.testing

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolverTestDslParsingTest {
    @Test
    fun `rejects injected errors in direct and nested registry argument expressions`() {
        for (value in listOf("\"ERROR\"", "{values: \"ERROR\"}", "{values: [1, \"ERROR\"]}")) {
            val selection = "dependency(arg: $value)".replace("\"", "\\\"")
            val failure = assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(of: "$selection", result: 1)
                      dependency(arg: Input!): Int! @resolver(result: 2)
                    }
                    input Input { values: [Int!]! }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains("Registry fragment contains erroneous arguments for Query/dependency"))
        }
    }

    @Test
    fun `rejects injected errors in variable bearing aliased provider paths`() {
        val failure = assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "provided: source(arg: \"ERROR\") byArg(arg: ${'$'}seed) untouched(arg: ${'$'}__resolverTestError0) consume(arg: ${'$'}fromSource)"
                      pathVars: [{name: "fromSource", path: ["provided"]}]
                      providerVars: {__resolverTestError0: 4}
                      result: 1
                    )
                  source(arg: Int!): Int! @resolver(result: 2)
                  byArg(arg: Int!): Int! @resolver(result: 3)
                  untouched(arg: Int!): Int! @resolver(result: 4)
                  consume(arg: Int!): Int! @resolver(result: 5)
                }
                """.trimIndent(),
            )
        }
        assertTrue(failure.message.orEmpty().contains("Registry fragment contains erroneous arguments for Query/source"))
    }

    @Test
    fun `rejects injected errors beneath symbolic guards and nested selections`() {
        val failure = assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(show: Boolean!): Int!
                    @resolver(of: "box { ... @include(if: ${'$'}show) { renamed: dependency(arg: \"ERROR\") } }", result: 1)
                  box: Box! @resolver(result: {})
                }
                type Box {
                  dependency(arg: Int!): Int! @resolver(result: 2)
                }
                """.trimIndent(),
            )
        }
        assertTrue(failure.message.orEmpty().contains("Registry fragment contains erroneous arguments for Box/dependency"))
    }

    @Test
    fun `injected argument errors do not become Boolean directive variables`() {
        for (selection in listOf(
            "dependency @include(if: \"ERROR\")",
            "dependency @skip(if: true) @include(if: \"ERROR\")",
            "... @include(if: \"ERROR\") { dependency }",
        )) {
            val escapedSelection = selection.replace("\"", "\\\"")
            val failure = assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(of: "$escapedSelection", result: 1)
                      dependency: Int! @resolver(result: 2)
                    }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains("Directive variables must be Boolean"))
        }
    }
}
