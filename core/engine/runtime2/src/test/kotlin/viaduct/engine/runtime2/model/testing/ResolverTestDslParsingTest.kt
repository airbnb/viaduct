package viaduct.engine.runtime2.model.testing

import org.apache.commons.jexl3.JexlException
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolverTestDslParsingTest {
    @Test
    fun `resolver selectivity requires a single boolean literal`() {
        for (value in listOf("null", "1", "\"true\"", "[]", "{}", "true, isSelective: false")) {
            for (source in listOf(
                "extend type Query { value: Int @resolver(script: \"1\", isSelective: $value) }",
                "type User implements Node @nodeResolver(ids: [\"one\"], script: \"{:}\", isSelective: $value) { id: ID! }",
            )) {
                val failure = assertThrows<IllegalArgumentException>(source) { TestWorld.fromDSL(source) }
                assertTrue(failure.message.orEmpty().contains("isSelective") || failure.message.orEmpty().contains("duplicate"), failure.message)
            }
        }
    }

    @Test
    fun `checker declarations validate scripts input names and paired metadata`() {
        for (declaration in listOf(
            "",
            "script: 1",
            "script: \"allow\", other: 1",
            "script: \"allow\", script: \"deny\"",
            "script: \"allow\", inputs: null",
            "script: \"allow\", inputs: [1]",
            "script: \"allow\", inputs: [{}]",
            "script: \"allow\", inputs: [{name: \"bad-name\"}]",
            "script: \"allow\", inputs: [{name: \"x\", of: 1}]",
            "script: \"allow\", inputs: [{name: \"x\", qf: null}]",
            "script: \"allow\", inputs: [{name: \"x\", result: 1}]",
            "script: \"allow\", inputs: [{name: \"x\", name: \"y\"}]",
            "script: \"allow\", inputs: [{name: \"x\"}, {name: \"x\"}]",
        )) {
            assertThrows<IllegalArgumentException>(declaration) { TestWorld.fromDSL("extend type Query { checked: Int @checker${if (declaration.isEmpty()) "" else "($declaration)"} }") }
        }
        for (source in listOf(
            "extend type Query { checked: Int @checker(script: \"allow\") @checker(script: \"allow\") }",
            "extend type Query @checker(script: \"allow\") @checker(script: \"allow\") { checked: Int }",
        )) {
            assertThrows<IllegalArgumentException>(source) { TestWorld.fromDSL(source) }
        }
    }

    @Test
    fun `checker pairs reject missing unused conflicting and argumentless variable sources`() {
        for ((metadata, diagnostic) in listOf(
            "" to "no source",
            "variables: [{name: \"v\", fromArgument: [\"missing\"]}]" to "has no argument",
            "variables: [{name: \"v\", fromQueryField: [\"missing\"]}]" to "no selection",
            "variables: [{name: \"v\", fromObjectField: [\"echo\"]}]" to "dependency cycle",
            "variables: [{name: \"unused\", fromArgument: [\"seed\"]}]" to "unused variables",
            "vars: {names: [\"seed\"], script: \"{'seed': 1}\"}" to "may not redefine",
            "variables: [{name: \"v\", fromArgument: [\"seed\"]}], vars: {names: [\"v\"], script: \"{'v': 1}\"}" to "may not redefine",
        )) {
            val failure = assertThrows<IllegalArgumentException>(metadata) {
                TestWorld.fromDSL(
                    """
                extend type Query {
                  checked(seed: Int): Int @checker(inputs: [{name: "input", of: "echo(value: ${'$'}v)", $metadata}], script: "allow")
                  echo(value: Int): Int @resolver(script: "value")
                }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains(diagnostic), failure.message)
        }
        val typeFailure = assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
            extend type Query @checker(inputs: [{name: "input", of: "echo(value: ${'$'}v)", variables: [{name: "v", fromArgument: ["seed"]}]}], script: "allow") {
              echo(value: Int): Int @resolver(script: "value")
            }
                """.trimIndent(),
            )
        }
        assertTrue(typeFailure.message.orEmpty().contains("Type checker variables cannot be defined from an argument"), typeFailure.message)
        for (name in listOf("inputs", "allow", "deny", "error")) {
            assertThrows<IllegalArgumentException>(name) { TestWorld.fromDSL("extend type Query { checked($name: Int): Int @checker(script: \"allow\") }") }
        }
    }

    @Test
    fun `explicit variables require a name and exactly one well formed source path`() {
        for (declaration in listOf(
            "null",
            "1",
            "[null]",
            "{}",
            "{name: \"value\"}",
            "{fromArgument: [\"seed\"]}",
            "{name: \"bad-name\", fromArgument: [\"seed\"]}",
            "{name: \"value\", fromArgument: [\"seed\"], fromQueryField: [\"source\"]}",
            "{name: \"value\", fromArgument: [\"seed\"], extra: 1}",
            "{name: \"value\", fromArgument: [\"seed\"], name: \"other\"}",
            "{name: \"value\", fromArgument: []}",
            "{name: \"value\", fromArgument: \"seed\"}",
            "{name: \"value\", fromObjectField: [null]}",
            "{name: \"value\", fromQueryField: [\"bad-name\"]}",
            "[{name: \"value\", fromArgument: [\"seed\"]}, {name: \"value\", fromQueryField: [\"source\"]}]",
        )) {
            val failure = assertThrows<IllegalArgumentException>(declaration) {
                TestWorld.fromDSL("extend type Query { result(seed: Int): Int @resolver(variables: $declaration, script: \"1\") }")
            }
            assertTrue(failure.message.orEmpty().contains("variables"), failure.message)
        }
    }

    @Test
    fun `explicit variables reject duplicate sources and unused declarations`() {
        for ((declaration, diagnostic) in listOf(
            "variables: {name: \"unused\", fromArgument: [\"seed\"]}" to "Unused variables",
            "variables: {name: \"value\", fromArgument: [\"seed\"]}, pathVars: {name: \"value\", path: [\"source\"]}" to "may not redefine pathVars",
            "variables: {name: \"value\", fromArgument: [\"seed\"]}, vars: {names: [\"value\"], script: \"{'value': 1}\"}" to "may not redefine",
        )) {
            val failure = assertThrows<IllegalArgumentException>(declaration) {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result(seed: Int): Int @resolver(of: "source consume(value: ${'$'}value)", $declaration, script: "1")
                      source: Int @resolver(script: "2")
                      consume(value: Int): Int @resolver(script: "value")
                    }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains(diagnostic), failure.message)
        }
    }

    @Test
    fun `explicit sources use the existing input and response path validation`() {
        for ((source, diagnostic) in listOf(
            "fromArgument: [\"missing\"]" to "has no argument missing",
            "fromArgument: [\"input\", \"missing\"]" to "has no field missing",
            "fromArgument: [\"inputs\", \"value\"]" to "cannot traverse list",
            "fromObjectField: [\"missing\"]" to "no selection",
            "fromQueryField: [\"local\"]" to "no selection",
            "fromQueryField: [\"items\", \"value\"]" to "cannot traverse list",
        )) {
            val failure = assertThrows<IllegalArgumentException>(source) {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result(input: Input, inputs: [Input]): Int @resolver(
                        of: "local: source consume(value: ${'$'}value)"
                        qf: "remote: source items { value }"
                        variables: {name: "value", $source}
                        script: "1"
                      )
                      source: Int @resolver(script: "2")
                      items: [Item] @resolver(script: "[]")
                      consume(value: Int): Int @resolver(script: "value")
                    }
                    input Input { value: Int }
                    type Item { value: Int }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains(diagnostic), failure.message)
        }
    }

    @Test
    fun `validates scripted Node declarations`() {
        for (declaration in listOf(
            "result: [{id: 1, result: {}}]",
            "ids: [], script: \"{:}\"",
            "ids: [null], script: \"{:}\"",
            "ids: [true], script: \"{:}\"",
            "ids: [\"one\", \"one\"], script: \"{:}\"",
            "ids: [\"one\"], script: null",
            "ids: [\"one\"]",
            "script: \"{:}\"",
            "ids: [\"one\"], script: \"{:}\", result: []",
        )) {
            assertThrows<IllegalArgumentException>(declaration) {
                TestWorld.fromDSL("type User implements Node @nodeResolver($declaration) { id: ID! }")
            }
        }
        val failure = assertThrows<JexlException> {
            TestWorld.fromDSL("type User implements Node @nodeResolver(ids: [\"one\"], script: \"var x = ;\") { id: ID! }")
        }
        assertTrue(failure.message.orEmpty().contains("User/@nodeResolver"))
    }

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
                TestWorld.fromDSL("extend type Query { value: Int @resolver($declaration, script: \"1\") }")
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
                        script: "1"
                      )
                      source: Int @resolver(script: "2")
                      echo(value: Int): Int @resolver(script: "3")
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
                  value: Int @resolver(of: "echo(value: ${'$'}provided)", vars: {names: ["provided"], script: "var x = ;"}, script: "1")
                  echo(value: Int): Int @resolver(script: "2")
                }
                """.trimIndent()
            )
        }
        assertTrue(failure.message.orEmpty().contains("Query/value.vars"))
    }

    @Test
    fun `requires a string script and rejects legacy resolver arguments`() {
        for (arguments in listOf(
            "of: \"\"",
            "result: 1",
            "result: null",
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
        for (selection in listOf("dependency(arg: null)", "dependency(arg: 1) @include(if: null)")) {
            val escapedSelection = selection.replace("\"", "\\\"")
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(qf: "$escapedSelection", script: "1")
                      dependency(arg: Int!): Int! @resolver(script: "2")
                    }
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `rejects invalid direct and nested registry argument literals`() {
        for (value in listOf("null", "{values: null}", "{values: [1, null]}")) {
            val selection = "dependency(arg: $value)".replace("\"", "\\\"")
            val failure = assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(of: "$selection", script: "1")
                      dependency(arg: Input!): Int! @resolver(script: "2")
                    }
                    input Input { values: [Int!]! }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains("Invalid GraphQL fragment"))
        }
    }

    @Test
    fun `rejects invalid literals in variable bearing aliased provider paths`() {
        val failure = assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "provided: source(arg: null) byArg(arg: ${'$'}seed) untouched(arg: ${'$'}other) consume(arg: ${'$'}fromSource)"
                      pathVars: [{name: "fromSource", path: ["provided"]}]
                      vars: {names: ["other"], script: "{'other': 4}"}
                      script: "1"
                    )
                  source(arg: Int!): Int! @resolver(script: "2")
                  byArg(arg: Int!): Int! @resolver(script: "3")
                  untouched(arg: Int!): Int! @resolver(script: "4")
                  consume(arg: Int!): Int! @resolver(script: "5")
                }
                """.trimIndent(),
            )
        }
        assertTrue(failure.message.orEmpty().contains("Invalid GraphQL fragment"))
    }

    @Test
    fun `rejects invalid literals beneath symbolic guards and nested selections`() {
        val failure = assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(show: Boolean!): Int!
                    @resolver(of: "box { ... @include(if: ${'$'}show) { renamed: dependency(arg: null) } }", script: "1")
                  box: Box! @resolver(script: "{:}")
                }
                type Box {
                  dependency(arg: Int!): Int! @resolver(script: "2")
                }
                """.trimIndent(),
            )
        }
        assertTrue(failure.message.orEmpty().contains("Invalid GraphQL fragment"))
    }

    @Test
    fun `rejects nonboolean directive arguments even beneath inactive selections`() {
        for (selection in listOf(
            "dependency @include(if: null)",
            "dependency @skip(if: true) @include(if: null)",
            "... @include(if: null) { dependency }",
        )) {
            val escapedSelection = selection.replace("\"", "\\\"")
            val failure = assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(of: "$escapedSelection", script: "1")
                      dependency: Int! @resolver(script: "2")
                    }
                    """.trimIndent(),
                )
            }
            assertTrue(failure.message.orEmpty().contains("Invalid GraphQL fragment"))
        }
    }
}
