package viaduct.engine.runtime.execution

import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimpleInstrumentationContext
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.dataloader.NextTickDispatcher
import viaduct.engine.api.instrumentation.IViaductInstrumentation
import viaduct.engine.api.instrumentation.ViaductInstrumentationBase
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createViaductGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeQuery
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

class NestedTraversalSchedulingTest {
    @Test
    fun `queued descendants do not delay the parent fetch or a ready sibling`() =
        runTraversalTest { dispatcher ->
            withTimeout(10_000) {
                val siblingFetched = CompletableDeferred<Unit>()
                val descendantFetched = CompletableDeferred<Unit>()
                val parentFetch = ParentFetchObserver()
                val schema = createSchema(
                    "type Query { items: [Item] sibling: Int } type Item { value: Int }",
                    mapOf(
                        "Query" to mapOf(
                            "items" to DataFetcher {
                                dispatcher.pauseNextLaunch.set(true)
                                listOf(emptyMap<String, Any?>())
                            },
                            "sibling" to DataFetcher {
                                siblingFetched.complete(Unit)
                                2
                            },
                        ),
                        "Item" to mapOf(
                            "value" to DataFetcher {
                                descendantFetched.complete(Unit)
                                1
                            },
                        ),
                    ),
                )
                val graphQL = createViaductGraphQL(
                    schema,
                    instrumentations = listOf(parentFetch.asStandardInstrumentation),
                )
                val result = graphQL.executeAsync(createExecutionInput(schema, "{ items { value } sibling }"))
                try {
                    siblingFetched.await()
                    parentFetch.completed.await()
                    assertFalse(descendantFetched.isCompleted)
                    assertFalse(result.isDone)
                } finally {
                    dispatcher.releaseLaunch.complete(Unit)
                }
                val completed = result.await()
                assertTrue(completed.errors.isEmpty())
                assertEquals(
                    mapOf("items" to listOf(mapOf("value" to 1)), "sibling" to 2),
                    completed.getData<Map<String, Any?>>(),
                )
            }
        }

    @Test
    fun `adding synchronous scalar selections or list items does not add coroutine launches`() =
        runTraversalTest { dispatcher ->
            withTimeout(10_000) {
                suspend fun execute(
                    itemCount: Int,
                    selectionCount: Int
                ): Int {
                    val initialLaunches = dispatcher.asyncLaunchCount
                    val schema = createSchema(
                        "type Query { items: [Item] } type Item { value: Int }",
                        mapOf("Query" to mapOf("items" to DataFetcher { List(itemCount) { mapOf("value" to it) } })),
                    )
                    val graphQL = createViaductGraphQL(schema)
                    val selections = (0 until selectionCount).joinToString(" ") { "v$it: value" }
                    val result = executeQuery(schema, graphQL, "{ items { $selections } }", emptyMap())
                    assertTrue(result.errors.isEmpty())
                    assertEquals(
                        mapOf("items" to List(itemCount) { value -> (0 until selectionCount).associate { "v$it" to value } }),
                        result.getData<Map<String, Any?>>(),
                    )
                    return dispatcher.asyncLaunchCount - initialLaunches
                }

                val baseline = execute(itemCount = 1, selectionCount = 1)
                assertEquals(baseline, execute(itemCount = 1, selectionCount = 100), "Scalar selections must stay synchronous")
                assertEquals(baseline, execute(itemCount = 100, selectionCount = 1), "List items must share one traversal coroutine")
            }
        }

    @Test
    fun `scalar lists and nested scalar lists do not add coroutine launches`() =
        runTraversalTest { dispatcher ->
            withTimeout(10_000) {
                suspend fun execute(
                    type: String,
                    value: Any
                ): Int {
                    val initialLaunches = dispatcher.asyncLaunchCount
                    val schema = createSchema(
                        "type Query { value: $type }",
                        mapOf("Query" to mapOf("value" to DataFetcher { value })),
                    )
                    val graphQL = createViaductGraphQL(schema)
                    val result = executeQuery(schema, graphQL, "{ value }", emptyMap())
                    assertTrue(result.errors.isEmpty())
                    assertEquals(mapOf("value" to value), result.getData<Map<String, Any?>>())
                    return dispatcher.asyncLaunchCount - initialLaunches
                }

                val baseline = execute("Int", 1)
                assertEquals(baseline, execute("[Int]", listOf(1, 2)), "Scalar lists must stay synchronous")
                assertEquals(baseline, execute("[[Int]]", listOf(listOf(1), listOf(2, 3))), "Nested scalar lists must stay synchronous")
            }
        }

    @Test
    fun `next mutation waits for the previous nested payload to finish`() =
        runExecutionTest<Unit> {
            withTimeout(10_000) {
                val firstDescendantStarted = CompletableDeferred<Unit>()
                val releaseFirstDescendant = CompletableDeferred<Unit>()
                val secondMutationStarted = CompletableDeferred<Unit>()
                val schema = createSchema(
                    """
                    type Query { unused: Int }
                    type Mutation { first: Payload second: Payload }
                    type Payload { items: [Item] }
                    type Item { value: Int }
                    """.trimIndent(),
                    mapOf(
                        "Mutation" to mapOf(
                            "first" to DataFetcher { mapOf("items" to listOf(mapOf("value" to 1))) },
                            "second" to DataFetcher {
                                assertTrue(releaseFirstDescendant.isCompleted)
                                secondMutationStarted.complete(Unit)
                                mapOf("items" to listOf(mapOf("value" to 2)))
                            },
                        ),
                        "Item" to mapOf(
                            "value" to DataFetcher { environment ->
                                val value = environment.getSource<Map<String, Int>>()!!.getValue("value")
                                if (value == 1) {
                                    scopedFuture {
                                        firstDescendantStarted.complete(Unit)
                                        releaseFirstDescendant.await()
                                        value
                                    }
                                } else {
                                    value
                                }
                            },
                        ),
                    ),
                )
                val graphQL = createViaductGraphQL(schema)
                val result = graphQL.executeAsync(
                    createExecutionInput(schema, "mutation { first { items { value } } second { items { value } } }"),
                )
                try {
                    firstDescendantStarted.await()
                    assertFalse(secondMutationStarted.isCompleted)
                    assertFalse(result.isDone)
                } finally {
                    releaseFirstDescendant.complete(Unit)
                }
                val completed = result.await()
                assertTrue(completed.errors.isEmpty())
                assertEquals(
                    mapOf(
                        "first" to mapOf("items" to listOf(mapOf("value" to 1))),
                        "second" to mapOf("items" to listOf(mapOf("value" to 2))),
                    ),
                    completed.getData<Map<String, Any?>>(),
                )
            }
        }

    @Test
    fun `request cancellation reaches a pending nested descendant`() =
        runExecutionTest<Unit> {
            withTimeout(10_000) {
                val descendantStarted = CompletableDeferred<Unit>()
                val descendantCancelled = CompletableDeferred<Unit>()
                val schema = createSchema(
                    "type Query { items: [Item] } type Item { detail: Detail } type Detail { value: Int }",
                    mapOf(
                        "Query" to mapOf(
                            "items" to DataFetcher { listOf(mapOf("detail" to emptyMap<String, Any?>())) },
                        ),
                        "Detail" to mapOf(
                            "value" to DataFetcher {
                                scopedFuture {
                                    try {
                                        descendantStarted.complete(Unit)
                                        awaitCancellation()
                                    } finally {
                                        descendantCancelled.complete(Unit)
                                    }
                                }
                            },
                        ),
                    ),
                )
                val graphQL = createViaductGraphQL(schema)
                val request = async {
                    withThreadLocalCoroutineContext {
                        executeQuery(schema, graphQL, "{ items { detail { value } } }", emptyMap())
                    }
                }
                try {
                    descendantStarted.await()
                } finally {
                    request.cancelAndJoin()
                }
                descendantCancelled.await()
                assertTrue(request.isCancelled)
            }
        }

    private fun runTraversalTest(block: suspend (TraversalDispatcher) -> Unit) =
        runExecutionTest<Unit> {
            val dispatcher = TraversalDispatcher()
            withContext(NextTickDispatcher(dispatcher)) {
                withThreadLocalCoroutineContext {
                    block(dispatcher)
                }
            }
        }

    private class TraversalDispatcher : CoroutineDispatcher() {
        private val jobs = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap<Deferred<*>, Boolean>()))
        val asyncLaunchCount: Int get() = jobs.size
        val pauseNextLaunch = AtomicBoolean()
        val releaseLaunch = CompletableDeferred<Unit>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val job = context[Job]
            // Undispatched scopes can first reach the dispatcher on resume, without starting an async task.
            val firstDispatch = job is Deferred<*> && jobs.add(job)
            if (firstDispatch && pauseNextLaunch.compareAndSet(true, false)) {
                releaseLaunch.invokeOnCompletion { Dispatchers.Default.dispatch(context, block) }
            } else {
                Dispatchers.Default.dispatch(context, block)
            }
        }
    }

    private class ParentFetchObserver : ViaductInstrumentationBase(), IViaductInstrumentation.WithBeginFieldFetch {
        val completed = CompletableDeferred<Unit>()

        override fun beginFieldFetch(
            parameters: InstrumentationFieldFetchParameters,
            state: InstrumentationState?,
        ): InstrumentationContext<Any>? =
            if (parameters.executionStepInfo.path.toString() == "/items") {
                SimpleInstrumentationContext.whenCompleted { _, _ -> completed.complete(Unit) }
            } else {
                null
            }
    }
}
