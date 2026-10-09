package viaduct.engine.runtime.execution

import graphql.schema.DataFetcher
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createViaductGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

class PendingListTraversalContextTest {
    @Test
    fun `pending list can complete outside the request coroutine context`() =
        completeOnExternalThread(
            fieldName = "items",
            selections = "{ value }",
            payload = listOf(mapOf("value" to 1), mapOf("value" to 2)),
        )

    @Test
    fun `pending object with a ready nested list can complete outside the request coroutine context`() =
        completeOnExternalThread(
            fieldName = "container",
            selections = "{ items { value } }",
            payload = mapOf("items" to listOf(mapOf("value" to 1), mapOf("value" to 2))),
        )

    private fun <T : Any> completeOnExternalThread(
        fieldName: String,
        selections: String,
        payload: T,
    ) = runExecutionTest<Unit> {
        withTimeout(10_000) {
            val pending = CompletableFuture<T>()
            val siblingFetched = CompletableDeferred<Unit>()
            val schema = createSchema(
                """
                type Query { items: [Item] container: Container sibling: Int }
                type Container { items: [Item] }
                type Item { value: Int }
                """.trimIndent(),
                mapOf(
                    "Query" to mapOf(
                        fieldName to DataFetcher { pending },
                        "sibling" to DataFetcher {
                            siblingFetched.complete(Unit)
                            3
                        },
                    ),
                ),
            )
            val graphQL = createViaductGraphQL(schema)
            val result = graphQL.executeAsync(createExecutionInput(schema, "{ $fieldName $selections sibling }"))
            val executor = Executors.newSingleThreadExecutor()
            try {
                siblingFetched.await()
                assertFalse(pending.isDone)
                assertFalse(result.isDone)
                CompletableFuture.runAsync({ pending.complete(payload) }, executor).await()
                val completed = result.await()
                assertTrue(completed.errors.isEmpty(), completed.errors.toString())
                assertEquals(mapOf(fieldName to payload, "sibling" to 3), completed.getData<Map<String, Any?>>())
            } finally {
                pending.cancel(true)
                result.cancel(true)
                executor.shutdownNow()
            }
        }
    }
}
