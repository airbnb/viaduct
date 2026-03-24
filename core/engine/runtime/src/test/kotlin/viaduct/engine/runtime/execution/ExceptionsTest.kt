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
