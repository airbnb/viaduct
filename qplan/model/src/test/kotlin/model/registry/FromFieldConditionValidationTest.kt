package model.registry

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import model.Arguments
import model.emptyFragmentOf
import model.fragmentFrom
import model.requireObjectField
import model.testing.TestWorld

class FromFieldConditionValidationTest {
    @Test
    fun `statically excluded provider paths are rejected even for nullable consumers`() {
        for (provider in ProviderFragment.entries) for (directive in listOf("@include(if: false)", "@skip(if: true)")) {
            val failure = assertFailsWith<IllegalArgumentException> {
                world(provider, "a $directive", indirect = false)
            }
            assertTrue(failure.message.orEmpty().contains("statically excluded"), failure.message)
        }
    }

    @Test
    fun `provider inclusion cannot depend on its own variable`() {
        for (provider in ProviderFragment.entries) {
            val failure = assertFailsWith<IllegalArgumentException> {
                world(provider, "a @include(if: ${'$'}value)", indirect = false)
            }
            assertTrue(failure.message.orEmpty().contains("cycle"), failure.message)
        }
    }

    @Test
    fun `provider inclusion variables cannot form an indirect cycle`() {
        for (provider in ProviderFragment.entries) {
            val failure = assertFailsWith<IllegalArgumentException> {
                world(provider, "a @include(if: ${'$'}other) b @skip(if: ${'$'}value)", indirect = true)
            }
            assertTrue(failure.message.orEmpty().contains("cycle"), failure.message)
        }
    }

    private fun world(
        provider: ProviderFragment,
        source: String,
        indirect: Boolean
    ): TestWorld {
        val fragmentSource = "fragment Input on Query { $source consume(value: ${'$'}value) }"
        return TestWorld.fromSDL(
            schemaSDL = """
                type Query {
                  outer: Int!
                  a: Boolean!
                  b: Boolean!
                  consume(value: Boolean): Int!
                }
            """.trimIndent(),
            fieldResolvers = { schema ->
                val outer = schema.loweredSchema.requireObjectField("Query", "outer")
                val fragment = schema.fragmentFrom(fragmentSource, variableField = outer)
                mapOf(
                    outer to fieldResolverOf(
                        objectFragment = if (provider == ProviderFragment.OBJECT) fragment else schema.loweredSchema.emptyFragmentOf("Query"),
                        queryFragment = if (provider == ProviderFragment.QUERY) fragment else schema.loweredSchema.emptyFragmentOf("Query"),
                    ) { _, _, _ -> 1 },
                    schema.loweredSchema.requireObjectField("Query", "a") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> true },
                    schema.loweredSchema.requireObjectField("Query", "b") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> true },
                    schema.loweredSchema.requireObjectField("Query", "consume") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                )
            },
            variableProviders = { schema ->
                val outer = schema.loweredSchema.requireObjectField("Query", "outer")

                fun fromPath(name: String) =
                    when (provider) {
                        ProviderFragment.OBJECT -> schema.fromObjectField(fragmentSource, listOf(name), outer)
                        ProviderFragment.QUERY -> schema.fromQueryField(fragmentSource, listOf(name), outer)
                    }
                buildMap {
                    put(Arguments.Variable.of(outer, "value"), fromPath("a"))
                    if (indirect) put(Arguments.Variable.of(outer, "other"), fromPath("b"))
                }
            },
        )
    }
}
