package viaduct.engine.runtime2.execution

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionContextBuilder
import graphql.execution.ExecutionId
import graphql.language.Document
import graphql.language.OperationDefinition
import graphql.parser.Parser
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom

class OperationSelectionCacheTest {
    private val world =
        TestWorld.fromDSL(
            """
            extend type Query { value(seed: Int): Int @resolver(result: 1) }
            """.trimIndent(),
        )

    @Test
    fun `one document instance decodes a variable-free query once`() {
        val cache = OperationSelectionCache()
        val decoder = CountingDecoder()
        val document = Parser().parseDocument("query { value }")
        val first = cache.selections(world.assumptions, context(document), decoder::decode)
        val second = cache.selections(world.assumptions, context(document), decoder::decode)
        assertSame(first, second)
        assertEquals(1, decoder.calls)

        val reparsed = cache.selections(world.assumptions, context(Parser().parseDocument("query { value }")), decoder::decode)
        assertNotSame(first, reparsed, "Equal text in another document instance is a different key")
        assertEquals(2, decoder.calls)
    }

    @Test
    fun `operations with variables are cached per coerced value set`() {
        val cache = OperationSelectionCache()
        val decoder = CountingDecoder()
        val document = Parser().parseDocument("query(\$seed: Int) { value(seed: \$seed) }")
        val one = cache.selections(world.assumptions, context(document, mapOf("seed" to 1)), decoder::decode)
        assertSame(one, cache.selections(world.assumptions, context(document, mapOf("seed" to 1)), decoder::decode))
        val two = cache.selections(world.assumptions, context(document, mapOf("seed" to 2)), decoder::decode)
        assertNotSame(one, two)
        assertEquals(2, decoder.calls)
    }

    @Test
    fun `conditional documents and mutations decode on every request`() {
        val cache = OperationSelectionCache()
        val decoder = CountingDecoder()
        val conditional = Parser().parseDocument("query { value @skip(if: false) }")
        repeat(2) { cache.selections(world.assumptions, context(conditional), decoder::decode) }
        val conditionalFragment = Parser().parseDocument("query { ...F } fragment F on Query { value @include(if: true) }")
        repeat(2) { cache.selections(world.assumptions, context(conditionalFragment), decoder::decode) }
        val mutation = Parser().parseDocument("mutation { value }")
        repeat(2) { cache.selections(world.assumptions, context(mutation), decoder::decode) }
        assertEquals(6, decoder.calls)
    }

    private fun context(
        document: Document,
        variables: Map<String, Any?> = emptyMap(),
    ): ExecutionContext =
        ExecutionContextBuilder.newExecutionContextBuilder()
            .executionId(ExecutionId.generate())
            .graphQLSchema(world.schemas.graphQLSchema)
            .graphQLContext(GraphQLContext.getDefault())
            .document(document)
            .operationDefinition(document.getDefinitionsOfType(OperationDefinition::class.java).single())
            .coercedVariables(CoercedVariables.of(variables))
            .locale(Locale.ROOT)
            .build()

    private inner class CountingDecoder {
        var calls = 0

        /** Each decode parses a distinct forest instance, so reuse is observable by identity. */
        fun decode(): SelectionForest {
            calls++
            return world.schemas.fragmentFrom("fragment F on Query { value }").subselections
        }
    }
}
