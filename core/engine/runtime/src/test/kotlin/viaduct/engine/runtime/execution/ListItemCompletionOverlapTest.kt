package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.parameters.InstrumentationFieldCompleteParameters
import graphql.schema.DataFetcher
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createViaductGraphQL

@OptIn(ExperimentalCoroutinesApi::class)
class ListItemCompletionOverlapTest {
    @Test
    fun `ready list item completes its nested scalar while another item is still fetching`() =
        runTest {
            val scheduler = testScheduler
            withThreadLocalCoroutineContext {
                val children = List(2) { CompletableFuture<Map<String, Int>>() }
                val requestedItems = mutableSetOf<Int>()
                val instrumentation = RecordingInstrumentation()
                val schema = createSchema(
                    """
                    type Query { items: [Item] }
                    type Item { child: Child }
                    type Child { value: Int }
                    """.trimIndent(),
                    mapOf(
                        "Query" to mapOf(
                            "items" to DataFetcher { children.indices.map { mapOf("id" to it) } },
                        ),
                        "Item" to mapOf(
                            "child" to DataFetcher { environment ->
                                val index = environment.getSource<Map<String, Int>>()!!.getValue("id")
                                requestedItems.add(index)
                                children[index]
                            },
                        ),
                    ),
                )
                val graphQL = createViaductGraphQL(
                    schema,
                    instrumentations = listOf(instrumentation),
                )
                val result = graphQL.executeAsync(createExecutionInput(schema, "{ items { child { value } } }"))
                val firstScalarCompletedBeforeSecondItem = try {
                    scheduler.runCurrent()
                    assertEquals(setOf(0, 1), requestedItems)

                    children[0].complete(mapOf("value" to 10))
                    scheduler.runCurrent()
                    assertFalse(children[1].isDone)
                    assertFalse(result.isDone)

                    // A field-completion callback runs after scalar serialization. Drain all
                    // runnable work, then snapshot this before releasing the other item.
                    instrumentation.fieldCompletionContexts.any { context ->
                        val parameters = context.parameters as InstrumentationFieldCompleteParameters
                        parameters.executionStepInfo.path.toList() == listOf("items", 0, "child", "value") &&
                            context.onCompletedCalled.get() &&
                            context.completedValue == 10 &&
                            context.completedException == null
                    }
                } finally {
                    children.forEachIndexed { index, child -> child.complete(mapOf("value" to 10 + index)) }
                    scheduler.runCurrent()
                }

                val completed = result.await()
                assertTrue(completed.errors.isEmpty(), completed.errors.toString())
                assertEquals(
                    mapOf("items" to listOf(mapOf("child" to mapOf("value" to 10)), mapOf("child" to mapOf("value" to 11)))),
                    completed.getData<Map<String, Any?>>(),
                )
                assertTrue(
                    firstScalarCompletedBeforeSecondItem,
                    "The ready item's nested scalar should complete before the other item's child value resolves",
                )
            }
        }
}
