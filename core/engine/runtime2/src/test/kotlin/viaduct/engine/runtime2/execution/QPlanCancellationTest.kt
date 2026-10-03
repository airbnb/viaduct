@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.execution

import graphql.incremental.DeferPayload
import graphql.incremental.DelayedIncrementalPartialResult
import graphql.incremental.IncrementalExecutionResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixture
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.ResolutionDispatcherFactory

class QPlanCancellationTest {
    @Test
    fun `caller cancellation bridge handles cancellation before request startup`() {
        val cancellation = CancellationException("caller cancelled before request startup")
        val bridge = QPlanCallerCancellation()
        val requestJob = Job()

        bridge.cancel(cancellation)
        bridge.attach(requestJob)

        assertTrue(requestJob.isCancelled)
    }

    @Test
    fun `caller cancellation bridge releases a completed request job`() {
        val bridge = QPlanCallerCancellation()
        val completedRequest = Job()
        bridge.attach(completedRequest)

        completedRequest.complete()
        val laterRequest = Job()
        bridge.attach(laterRequest)

        assertTrue(completedRequest.isCompleted)
        assertTrue(laterRequest.isActive)
        laterRequest.cancel()
    }

    @Test
    fun `future bridge terminates when cancellation prevents coroutine entry`() {
        val requestJob = Job()
        val requestScope = CoroutineScope(requestJob)
        var bridgeStarted = false
        requestJob.cancel(CancellationException("request already cancelling"))

        val future =
            requestScope.asCompletableFuture {
                bridgeStarted = true
                1
            }
        val failure = assertFailsWith<ExecutionException> { future.get(5, TimeUnit.SECONDS) }

        assertFalse(bridgeStarted)
        val bridgeFailure = assertIs<CoroutineCancellationBridgeException>(failure.cause)
        assertTrue(
            assertIs<CancellationException>(bridgeFailure.cause)
                .message
                .orEmpty()
                .contains("request already cancelling"),
        )
        assertTrue(future.isDone)
        assertFalse(future.isCancelled)
    }

    @Test
    fun `task-local cancellation crosses ordinary GraphQL completion`() =
        runBlocking {
            forEachThreadCount { threadCount, coroutineContext ->
                val result =
                    cancellationFixture(coroutineContext)
                        .runQueryAsync("query { fast cancelled }")
                        .get(5, TimeUnit.SECONDS)

                assertEquals(
                    mapOf("fast" to 1, "cancelled" to null),
                    result.getData(),
                    "threadCount=$threadCount",
                )
                assertTrue(
                    result.errors.single().message.contains("provider cancelled"),
                    "threadCount=$threadCount",
                )
            }
        }

    @Test
    fun `task-local cancellation crosses deferred GraphQL completion`() =
        runBlocking {
            forEachThreadCount { threadCount, coroutineContext ->
                val initial =
                    assertIs<IncrementalExecutionResult>(
                        cancellationFixture(coroutineContext)
                            .runQueryAsync(CANCELLATION_DEFER_QUERY, incrementalSupport = true)
                            .get(5, TimeUnit.SECONDS),
                    )
                assertEquals(mapOf("fast" to 1), initial.getData())

                val delayed =
                    initial.incrementalItemPublisher
                        .nextCancellationResult()
                        .get(5, TimeUnit.SECONDS)
                val payload = assertIs<DeferPayload>(delayed.incremental.single())

                assertEquals(mapOf("cancelled" to null), payload.getData())
                assertTrue(
                    payload.errors.single().message.contains("provider cancelled"),
                    "threadCount=$threadCount",
                )
                assertFalse(delayed.hasNext())
            }
        }

    @Test
    fun `list element exception is local with successful siblings`() =
        runBlocking {
            forEachThreadCount { threadCount, coroutineContext ->
                val pendingStarted = CompletableDeferred<Unit>()
                val failingStarted = CompletableDeferred<Unit>()
                val allowResults = CompletableDeferred<Unit>()
                val fixture =
                    listFailureFixture(
                        coroutineContext = coroutineContext,
                        pendingStarted = pendingStarted,
                        failingStarted = failingStarted,
                        allowResults = allowResults,
                    )
                val initial =
                    assertIs<IncrementalExecutionResult>(
                        fixture
                            .runQueryAsync(LIST_DEFER_QUERY, incrementalSupport = true)
                            .get(5, TimeUnit.SECONDS),
                    )
                assertEquals(mapOf("fast" to 1), initial.getData())
                withTimeout(5_000) {
                    pendingStarted.await()
                    failingStarted.await()
                }
                val next = initial.incrementalItemPublisher.nextCancellationResult()

                allowResults.complete(Unit)

                val delayed = next.get(5, TimeUnit.SECONDS)
                val payload = assertIs<DeferPayload>(delayed.incremental.single())
                assertEquals(mapOf("numbers" to listOf(0, null, 2)), payload.getData())
                val message = payload.errors.single().message
                assertTrue(
                    message.contains("list element bug"),
                    "threadCount=$threadCount, message=$message",
                )
                assertFalse(delayed.hasNext())
            }
        }

    private suspend fun forEachThreadCount(test: suspend (threadCount: Int, coroutineContext: CoroutineContext) -> Unit) {
        listOf(1, 4).forEach { threadCount ->
            ResolutionDispatcherFactory
                .create(threadCount)
                .use { dispatcher -> test(threadCount, dispatcher) }
        }
    }

    private fun cancellationFixture(coroutineContext: CoroutineContext): ExecutionTestFixture {
        val world =
            TestWorld.fromSDL(
                schemaSDL = CANCELLATION_SCHEMA,
                fieldResolvers = { schema ->
                    val fast = schema.loweredSchema.requireObjectField("Query", "fast")
                    val cancelled = schema.loweredSchema.requireObjectField("Query", "cancelled")
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        fast to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        cancelled to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment Cancelled on Query { " +
                                        "dependency @include(if: ${'$'}ready) }",
                                    variableField = cancelled,
                                ),
                            ) { _, _ -> 2 }
                                .withVariablesProvider(setOf("ready")) {
                                    throw CancellationException("provider cancelled")
                                },
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 3 },
                    )
                },
            )
        return ExecutionTestFixture.fromWorld(
            schemaSDL = CANCELLATION_SCHEMA,
            world = world,
            resolverCoroutineContext = coroutineContext,
        )
    }

    private fun listFailureFixture(
        coroutineContext: CoroutineContext,
        pendingStarted: CompletableDeferred<Unit>,
        failingStarted: CompletableDeferred<Unit>,
        allowResults: CompletableDeferred<Unit>,
    ): ExecutionTestFixture {
        val world =
            TestWorld.fromSDL(
                schemaSDL = LIST_SCHEMA,
                fieldResolvers = { schema ->
                    val fast = schema.loweredSchema.requireObjectField("Query", "fast")
                    val numbers = schema.loweredSchema.requireObjectField("Query", "numbers")
                    val number = schema.loweredSchema.requireObjectField("Query", "number")
                    val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                    mapOf(
                        fast to fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 1 },
                        numbers to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                                listOf(
                                    RootFieldReferenceData.of(
                                        listOf(number),
                                        mapOf("id" to 0),
                                    ),
                                    RootFieldReferenceData.of(
                                        listOf(number),
                                        mapOf("id" to 1),
                                    ),
                                    2,
                                )
                            },
                        number to
                            fieldResolverOf(
                                objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment NumberQuery on Query { " +
                                            "dependency @include(if: ${'$'}ready) }",
                                        variableField = number,
                                    ),
                            ) { _, _, arguments ->
                                arguments.fieldValues.getValue("id")
                            }.withVariablesProvider(setOf("ready")) { arguments ->
                                when (arguments.fieldValues.getValue("id")) {
                                    0 -> {
                                        pendingStarted.complete(Unit)
                                        allowResults.await()
                                        mapOf("ready" to false)
                                    }
                                    1 -> {
                                        failingStarted.complete(Unit)
                                        allowResults.await()
                                        throw NullPointerException("list element bug")
                                    }
                                    else -> error("Unexpected number reference")
                                }
                            },
                        dependency to
                            fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 3 },
                    )
                },
            )
        return ExecutionTestFixture.fromWorld(
            schemaSDL = LIST_SCHEMA,
            world = world,
            resolverCoroutineContext = coroutineContext,
        )
    }

    private companion object {
        val CANCELLATION_SCHEMA =
            """
            type Query {
              fast: Int!
              cancelled: Int
              dependency: Int!
            }
            """.trimIndent()

        val CANCELLATION_DEFER_QUERY =
            """
            query {
              fast
              ... @defer(label: "cancelled-part") {
                cancelled
              }
            }
            """.trimIndent()

        val LIST_SCHEMA =
            """
            type Query {
              fast: Int!
              numbers: [Int]
              number(id: Int!): Int!
              dependency: Int!
            }
            """.trimIndent()

        val LIST_DEFER_QUERY =
            """
            query {
              fast
              ... @defer(label: "numbers-part") {
                numbers
              }
            }
            """.trimIndent()
    }
}

private fun Publisher<DelayedIncrementalPartialResult>.nextCancellationResult(): CompletableFuture<DelayedIncrementalPartialResult> =
    CompletableFuture<DelayedIncrementalPartialResult>().also { result ->
        subscribe(
            object : Subscriber<DelayedIncrementalPartialResult> {
                override fun onSubscribe(subscription: Subscription) {
                    subscription.request(1)
                }

                override fun onNext(item: DelayedIncrementalPartialResult) {
                    result.complete(item)
                }

                override fun onError(throwable: Throwable) {
                    result.completeExceptionally(throwable)
                }

                override fun onComplete() {
                    if (!result.isDone) {
                        result.completeExceptionally(
                            IllegalStateException("Publisher completed without a result"),
                        )
                    }
                }
            },
        )
    }
