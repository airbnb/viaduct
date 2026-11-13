package viaduct.engine.runtime.validation

import graphql.ExecutionInput
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.parser.Parser
import graphql.validation.ValidationError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class IncrementalDirectiveValidationTest {
    private val provider = IncrementalDirectiveValidation(NoOpPreparsedDocumentProvider.INSTANCE)

    @ParameterizedTest
    @ValueSource(strings = ["inline", "fragment"])
    fun `label errors on inline fragments and fragment spreads use the containing field path`(kind: String) {
        val selection = if (kind == "inline") "... @defer(label: ${'$'}label) { value }" else "...Details @defer(label: ${'$'}label)"
        val fragment = if (kind == "fragment") "fragment Details on IncrementalItem { value }" else ""
        val error = labelError(
            "query(${'$'}label: String = \"details\") { item { $selection } } $fragment",
        )

        assertEquals(listOf("item"), error.queryPath)
    }

    @Test
    fun `label errors use field names through nested fields and aliases`() {
        val error = labelError(
            """
            query Named(${'$'}label: String = "details") {
                renamedItem: item {
                    renamedChild: child {
                        ... @defer(label: ${'$'}label) { value }
                    }
                }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("item", "child"), error.queryPath)
    }

    @Test
    fun `label errors in a reused fragment definition include the fragment name once`() {
        val error = labelError(
            """
            query(${'$'}label: String = "details") { item { ...Details ...Details } }
            fragment Details on IncrementalItem {
                child { ... @defer(label: ${'$'}label) { value } }
            }
            """.trimIndent(),
        )

        assertEquals(listOf("Details", "child"), error.queryPath)
    }

    @Test
    fun `label errors at the query root have no field path`() {
        val error = labelError(
            """
            query(${'$'}label: String = "details") {
                ... @defer(label: ${'$'}label) { item { value } }
            }
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), error.queryPath)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `duplicate label errors point to the conflicting use across experimental boundaries`(experimentalFirst: Boolean) {
        val experimental = "child @useExperimental { ... @defer(label: \"same\") { value } }"
        val stable = "... @defer(label: \"same\") { value }"
        val selections = if (experimentalFirst) "$experimental $stable" else "$stable $experimental"
        val error = labelError("{ item { $selections } }")

        assertEquals(if (experimentalFirst) listOf("item") else listOf("item", "child"), error.queryPath)
    }

    private fun labelError(query: String): ValidationError {
        val input = ExecutionInput.newExecutionInput().query(query).build()
        val entry = requireNotNull(
            provider.getDocumentAsync(input) { executionInput ->
                PreparsedDocumentEntry(Parser.parse(executionInput.query))
            }.join()
        )

        assertEquals(1, entry.errors.size, entry.errors.toString())
        return assertInstanceOf(ValidationError::class.java, entry.errors.single())
    }
}
