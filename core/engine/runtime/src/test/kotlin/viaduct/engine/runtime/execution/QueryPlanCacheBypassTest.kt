package viaduct.engine.runtime.execution

import graphql.ExecutionInput
import graphql.GraphQL
import graphql.GraphQLContext
import graphql.execution.preparsed.PreparsedDocumentEntry
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.parser.Parser
import graphql.schema.DataFetcher
import java.util.concurrent.CompletableFuture
import java.util.function.Function
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.context.setSkipQueryPlanCache
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createViaductGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

class QueryPlanCacheBypassTest {
    private val schema = createSchema(
        "type Query { x: Int y: Int }",
        mapOf("Query" to mapOf("x" to DataFetcher { 1 }, "y" to DataFetcher { 2 })),
    )

    @Test
    fun `a request marked to skip the query plan cache does not cache its plan for later requests`() {
        runExecutionTest {
            val graphQL = createGraphQL()

            val rewritten = graphQL.execute(rewriteDocument = true)
            val unchanged = graphQL.execute(rewriteDocument = false)

            assertEquals(mapOf("x" to 1), rewritten)
            assertEquals(mapOf("x" to 1, "y" to 2), unchanged)
        }
    }

    @Test
    fun `a request marked to skip the query plan cache does not reuse the plan cached for its query text`() {
        runExecutionTest {
            val graphQL = createGraphQL()

            val unchanged = graphQL.execute(rewriteDocument = false)
            val rewritten = graphQL.execute(rewriteDocument = true)

            assertEquals(mapOf("x" to 1, "y" to 2), unchanged)
            assertEquals(mapOf("x" to 1), rewritten)
        }
    }

    private fun createGraphQL(): GraphQL =
        createViaductGraphQL(
            schema,
            preparsedDocumentProvider = RewritingDocumentProvider(),
            queryPlanFactory = QueryPlanFactory.Cached(),
        )

    private suspend fun GraphQL.execute(rewriteDocument: Boolean): Map<String, Any?>? {
        val input = createExecutionInput(
            schema,
            QUERY,
            context = GraphQLContext.of(mapOf(REWRITE_DOCUMENT to rewriteDocument)),
        )
        val result = executeAsync(input).await()
        assertEquals(emptyList<Any>(), result.errors)
        return result.getData()
    }

    private class RewritingDocumentProvider : PreparsedDocumentProvider {
        override fun getDocumentAsync(
            executionInput: ExecutionInput,
            parseAndValidateFunction: Function<ExecutionInput, PreparsedDocumentEntry>,
        ): CompletableFuture<PreparsedDocumentEntry> {
            val entry = parseAndValidateFunction.apply(executionInput)
            if (executionInput.graphQLContext.get<Boolean>(REWRITE_DOCUMENT) != true) {
                return CompletableFuture.completedFuture(entry)
            }
            executionInput.setSkipQueryPlanCache(true)
            return CompletableFuture.completedFuture(PreparsedDocumentEntry(Parser.parse(REWRITTEN_QUERY)))
        }
    }

    private companion object {
        const val QUERY = "{ x y }"
        const val REWRITTEN_QUERY = "{ x }"
        const val REWRITE_DOCUMENT = "test.rewriteDocument"
    }
}
