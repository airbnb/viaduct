package viaduct.graphql.utils

import graphql.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DeferUsageTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "... on Query @defer { __typename }",
            "... on Query @defer(if: false) { __typename }",
            "nested { ... on Query @defer(if: \$later) { __typename } }",
            "... on Query @skip(if: true) @defer { __typename }",
        ]
    )
    fun `finds @defer even when its condition is false or the selection is skipped`(selections: String) {
        val parsed = parse("fragment Main on Query { $selections }")

        val usage = parsed.findDeferUsages().single()

        assertEquals("Main", usage.fragmentName)
        assertEquals(1, usage.sourceLocation!!.line)
        assertTrue(usage.toString().startsWith("@defer in fragment 'Main' at 1:"), usage.toString())
    }

    @Test
    fun `finds @defer on fragment spreads and in fragments with repeated or circular references`() {
        val parsed = parse(
            """
            fragment Main on Query { ...Outer ...Outer @defer }
            fragment Outer on Query { ...Inner }
            fragment Inner on Query { ...Main ... on Query @defer { __typename } }
            fragment Unused on Query { ... on Query @defer { __typename } }
            """.trimIndent()
        )

        val usages = parsed.findDeferUsages()

        assertEquals(2, usages.size)
        assertEquals(setOf("Main", "Inner"), usages.map { it.fragmentName }.toSet())
    }

    @Test
    fun `ignores @defer in comments, strings, and unused fragments`() {
        val parsed = parse(
            """
            fragment Main on Query {
                field(value: "@defer") @include(if: true)
                # @defer
            }
            fragment Unused on Query { ... on Query @defer { __typename } }
            """.trimIndent()
        )

        assertTrue(parsed.findDeferUsages().isEmpty())
    }

    @Test
    fun `continues checking selections after an undefined fragment spread`() {
        val parsed = parse("fragment Main on Query { ...Missing ... on Query @defer { __typename } }")

        assertEquals(1, parsed.findDeferUsages().size)
    }

    @Test
    fun `finds @defer without a fragment map`() {
        val selections = parse("fragment Main on Query { ... on Query @defer { __typename } }").selections
        val parsed = ParsedSelections("Query", selections, emptyMap())

        assertEquals(null, parsed.findDeferUsages().single().fragmentName)
        assertTrue(ParsedSelections.empty("Query").findDeferUsages().isEmpty())
    }

    private fun parse(document: String): ParsedSelections = ParsedSelections.fromDocument("Query", Parser.parse(document))
}
