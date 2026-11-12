package viaduct.engine.runtime2.model.testing

import org.apache.commons.jexl3.JexlException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolverTestDslParsingTest {
    @Test
    fun `requires a complete scripted variables provider declaration`() {
        for (declaration in listOf(
            "vars: null",
            "vars: 1",
            "vars: {}",
            "vars: {names: [\"provided\"]}",
            "vars: {script: \"{:}\"}",
            "vars: {names: [], script: \"{:}\"}",
            "vars: {names: \"provided\", script: \"{:}\"}",
            "vars: {names: [null], script: \"{:}\"}",
            "vars: {names: [1], script: \"{:}\"}",
            "vars: {names: [\"bad-name\"], script: \"{:}\"}",
            "vars: {names: [\"provided\", \"provided\"], script: \"{:}\"}",
            "vars: {names: [\"provided\"], script: null}",
            "vars: {names: [\"provided\"], script: 1}",
            "vars: {names: [\"provided\"], script: \"{:}\", extra: 1}",
            "vars: {names: [\"provided\"], script: \"{:}\", script: \"{:}\"}",
            "vars: {names: [\"provided\"], script: \"{:}\"}, vars: {names: [\"provided\"], script: \"{:}\"}",
            "vars: {names: [\"provided\"], script: \"{:}\"}, providerVars: {provided: 1}",
        )) {
            assertThrows<IllegalArgumentException>(declaration) {
                TestWorld.fromDSL("extend type Query { value: Int @resolver($declaration, result: 1) }")
            }
        }
    }

    @Test
    fun `scripted provider names cannot shadow arguments or path variables and must be used`() {
        for ((name, diagnostic) in listOf("seed" to "redefine", "fromSource" to "redefine", "unused" to "Unused vars variables")) {
            val failure = assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      value(seed: Int): Int @resolver(
                        of: "source echo(value: ${'$'}fromSource)"
                        pathVars: [{name: "fromSource", path: ["source"]}]
                        vars: {names: ["$name"], script: "{'$name': 1}"}
                        result: 1
                      )
                      source: Int @resolver(result: 2)
                      echo(value: Int): Int @resolver(result: 3)
                    }
                    """.trimIndent()
                )
            }
            assertTrue(failure.message.orEmpty().contains(diagnostic), failure.message)
        }
    }

    @Test
    fun `reports variable script syntax errors when constructing a DSL world`() {
        val failure = assertThrows<JexlException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  value: Int @resolver(of: "echo(value: ${'$'}provided)", vars: {names: ["provided"], script: "var x = ;"}, result: 1)
                  echo(value: Int): Int @resolver(result: 2)
                }
                """.trimIndent()
            )
        }
        assertTrue(failure.message.orEmpty().contains("Query/value.vars"))
    }

    @Test
    fun `requires exactly one result or string script`() {
        for (arguments in listOf(
            "result: 1, script: \"2\"",
            "result: null, script: \"null\"",
            "script: null",
            "script: 1",
            "script: \"1\", script: \"2\"",
            "result: 1, result: 2",
        )) {
            assertThrows<IllegalArgumentException>(arguments) {
                TestWorld.fromDSL("extend type Query { value: Int @resolver($arguments) }")
            }
        }
    }

    @Test
    fun `reports script syntax errors when constructing a DSL world`() {
        val failure = assertThrows<JexlException> {
            TestWorld.fromDSL("extend type Query { value: Int @resolver(script: \"var x = ;\") }")
        }
        assertTrue(failure.message.orEmpty().contains("Query/value"))
    }

    @Test
    fun `rejects erroneous arguments and directive bindings in Query fragments`() {
        for (selection in listOf("dependency(arg: \"ERROR\")", "dependency(arg: 1) @include(if: \"ERROR\")")) {
            val escapedSelection = selection.replace("\"", "\\\"")
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(qf: "$escapedSelection", result: 1)
                      dependency(arg: Int!): Int! @resolver(result: 2)
                    }
                    """.trimIndent(),
                )
            }
        }
    }

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
