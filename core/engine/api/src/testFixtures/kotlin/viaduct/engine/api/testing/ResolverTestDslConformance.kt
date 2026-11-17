package viaduct.engine.api.testing

object ResolverTestDslConformance {
    data class RejectedOutput(
        val name: String,
        val type: String,
        val script: String,
        val selection: String,
        val diagnostic: String,
    ) {
        val schemaSDL: String = """
            extend type Query { value: $type @resolver(script: "$script") }
            enum Kind { SMALL LARGE }
            interface Value { x: Int }
            type Item implements Value { x: Int, echo(value: Int): Int }
            type Other { x: Int }
        """.trimIndent()
        val query: String = "{ value $selection }"
    }

    val rejectedOutputs: List<RejectedOutput> = listOf(
        RejectedOutput("nonfinite float", "Float", "1.0e999d", "", "finite number"),
        RejectedOutput("integer overflow", "Int", "2147483648L", "", "outside GraphQL Int range"),
        RejectedOutput("unknown enum", "Kind", "'UNKNOWN'", "", "Kind"),
        RejectedOutput("invalid list element", "[Int]", "[1, 'wrong']", "", "Query/value[1]"),
        RejectedOutput("unknown object field", "Item", "{'x': 1, 'unexpected': 2}", "{ x }", "unexpected"),
        RejectedOutput("argument-bearing output field", "Item", "{'x': 1, 'echo': 2}", "{ x }", "argument"),
        RejectedOutput("missing abstract typename", "Value", "{'x': 1}", "{ x }", "__typename"),
        RejectedOutput("impossible abstract typename", "Value", "{'__typename': 'Other', 'x': 1}", "{ x }", "not a possible"),
        RejectedOutput("nonobject typename", "Value", "{'__typename': 'Kind'}", "{ x }", "__typename"),
    )

    val rejectedDeclarations: List<Pair<String, String>> = listOf(
        "missing variable source" to declaration("", field = "total"),
        "variable dependency cycle" to declaration("variables: [{name: \"v\", fromObjectField: [\"echo\"]}]"),
        "unused explicit variable" to declaration("variables: [{name: \"unused\", fromArgument: [\"v\"]}]"),
        "path variable shadows argument" to declaration("pathVars: [{name: \"v\", path: [\"seed\"]}]"),
        "explicit variable shadows path variable" to declaration(
            "pathVars: [{name: \"v\", path: [\"seed\"]}], variables: [{name: \"v\", fromObjectField: [\"seed\"]}]",
            field = "total",
        ),
        "provider shadows argument" to declaration("vars: {names: [\"v\"], script: \"{'v': 3}\"}"),
        "provider shadows explicit variable" to declaration(
            "variables: [{name: \"v\", fromObjectField: [\"seed\"]}], vars: {names: [\"v\"], script: \"{'v': 3}\"}",
            field = "total",
        ),
        "unused provider variable" to declaration("vars: {names: [\"unused\"], script: \"{'unused': 3}\"}"),
    )

    val acceptedDeclarations: List<Pair<String, String>> = listOf(
        "inferred argument variable" to declaration(""),
        "explicit variable overrides argument inference" to declaration("variables: [{name: \"v\", fromObjectField: [\"seed\"]}]"),
        "renamed nested argument" to declaration("variables: [{name: \"v\", fromArgument: [\"input\", \"nested\"]}]", field = "total(input: Input)"),
        "provider variable" to declaration("vars: {names: [\"v\"], script: \"{'v': 3}\"}", field = "total"),
    )

    data class AcceptedCheckerDeclaration(
        val name: String,
        val schemaSDL: String,
        val query: String = "{ checked(v: 11) }",
    )

    val rejectedCheckerDeclarations: List<Pair<String, String>> = listOf(
        "unused checker variable" to checkerDeclaration("variables: [{name: \"unused\", fromQueryField: [\"seed\"]}]"),
        "unused checker provider" to checkerDeclaration("vars: {names: [\"unused\"], script: \"{'unused': 3}\"}"),
        "checker provider shadows argument" to checkerDeclaration("vars: {names: [\"v\"], script: \"{'v': 3}\"}"),
        "checker provider shadows explicit variable" to checkerDeclaration(
            "variables: [{name: \"v\", fromQueryField: [\"seed\"]}], vars: {names: [\"v\"], script: \"{'v': 3}\"}",
        ),
        "missing checker variable source" to checkerDeclaration("", field = "checked"),
        "type checker cannot read field arguments" to checkerDeclaration("variables: [{name: \"v\", fromArgument: [\"v\"]}]", typeChecker = true),
    )

    val acceptedCheckerDeclarations: List<AcceptedCheckerDeclaration> = listOf(
        AcceptedCheckerDeclaration(
            "inferred checker argument",
            checkerDeclaration("", script = "inputs.first.queryValue.echo == v ? allow : deny"),
        ),
        AcceptedCheckerDeclaration(
            "explicit checker source overrides argument inference",
            checkerDeclaration("variables: [{name: \"v\", fromObjectField: [\"seed\"]}]", script = "inputs.first.queryValue.echo == 3 ? allow : deny"),
        ),
        AcceptedCheckerDeclaration(
            "renamed nested checker argument",
            checkerDeclaration("variables: [{name: \"v\", fromArgument: [\"input\", \"nested\"]}]", field = "checked(input: Input)", script = "inputs.first.queryValue.echo == 5 ? allow : deny"),
            "{ checked(input: {nested: 5}) }",
        ),
        AcceptedCheckerDeclaration(
            "checker pairs reuse provider names independently",
            checkerDeclaration(
                "vars: {names: [\"v\"], script: \"{'v': 2}\"}",
                field = "checked",
                secondInput = ", {name: \"second\", qf: \"echo(value: ${'$'}v)\", vars: {names: [\"v\"], script: \"{'v': 3}\"}}",
                script = "inputs.first.queryValue.echo == 2 && inputs.second.queryValue.echo == 3 ? allow : deny",
            ),
            "{ checked }",
        ),
    )

    private fun checkerDeclaration(
        variables: String,
        field: String = "checked(v: Int)",
        secondInput: String = "",
        script: String = "allow",
        typeChecker: Boolean = false,
    ): String {
        val checker = """@checker(inputs: [{name: "first", of: "seed", qf: "echo(value: ${'$'}v)", $variables}$secondInput], script: "$script")"""
        return """
            input Input { nested: Int }
            extend type Query {
                seed: Int @resolver(script: "3")
                echo(value: Int): Int @resolver(script: "value")
                $field: ${if (typeChecker) "Item" else "Int"} @resolver(script: "${if (typeChecker) "{'value': 7, 'seed': 3}" else "7"}") ${if (typeChecker) "" else checker}
            }
            type Item ${if (typeChecker) checker else ""} { seed: Int, value: Int }
            """.trimIndent()
    }

    private fun declaration(
        variables: String,
        field: String = "total(v: Int)",
    ): String =
        """
        input Input { nested: Int }
        extend type Query {
            seed: Int @resolver(script: "3")
            echo(value: Int): Int @resolver(script: "value")
            $field: Int @resolver(of: "seed echo(value: ${'$'}v)", $variables, script: "objectValue.echo")
        }
        """.trimIndent()
}
