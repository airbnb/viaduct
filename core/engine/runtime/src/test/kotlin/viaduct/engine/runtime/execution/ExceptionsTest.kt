package viaduct.engine.runtime.execution

import graphql.ExceptionWhileDataFetching
import graphql.schema.DataFetcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.exceptions.FieldFetchingException
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@ExperimentalCoroutinesApi
class ExceptionsTest {
    @Test
    fun `simple sync data fetcher`() {
        val exception = execute(
            "{ field }",
            mapOf(
                "Query" to mapOf(
                    "field" to DataFetcher {
                        throw RuntimeException()
                    }
                )
            )
        )
        assertTrue(exception is FieldFetchingException)
    }

    @Test
    fun `simple async data fetcher`() {
        val exception = execute(
            "{ field }",
            mapOf(
                "Query" to mapOf(
                    "field" to DataFetcher {
                        scopedFuture {
                            throw RuntimeException()
                        }
                    }
                )
            )
        )
        assertTrue(exception is FieldFetchingException, "Expected FieldFetchingException but got ${exception?.javaClass}: $exception")
    }

    @Test
    fun `resolver returning a non-iterable for a list field surfaces a field error`() =
        runExecutionTest {
            val result = ExecutionTestHelpers.executeViaductModernGraphQL(
                sdl = "type Query { items: [String] }",
                // Resolver returns a scalar where the schema declares a list type
                resolvers = mapOf("Query" to mapOf("items" to DataFetcher { "not-a-list" })),
                query = "{ items }",
            )
            assertTrue(result.errors.isNotEmpty(), "Expected an error when a list field resolves to a non-iterable")
            assertTrue(
                result.errors[0].message.contains("Expected data to be an Iterable"),
                "Expected 'Expected data to be an Iterable' error, got: ${result.errors[0].message}",
            )
        }

    private val defaultSdl = "type Query { field: Int }"

    private fun execute(
        query: String,
        resolvers: Map<String, Map<String, DataFetcher<*>>>,
        sdl: String = defaultSdl
    ): Throwable? =
        runExecutionTest {
            val result = ExecutionTestHelpers.executeViaductModernGraphQL(sdl, resolvers, query)
            result.errors.firstOrNull()?.let {
                (it as ExceptionWhileDataFetching).exception
            }
        }
}
