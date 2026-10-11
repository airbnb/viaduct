@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.execution

import graphql.Directives
import graphql.ExecutionInput
import graphql.GraphQL
import graphql.incremental.DeferPayload
import graphql.incremental.DelayedIncrementalPartialResult
import graphql.incremental.IncrementalExecutionResult
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.FieldResolverTask
import viaduct.engine.runtime2.resolution.framework.ResolverReadCycleException
import viaduct.engine.runtime2.resolution.framework.fieldResolverCycleTask
import viaduct.engine.runtime2.resolution.framework.valueCycleSlot

@OptIn(ExperimentalCoroutinesApi::class)
class QPlanRequestLifetimeTest {
    @Test
    fun `queries and mutations can complete after the former production deadline`() =
        runTest {
            for (type in listOf("Query", "Mutation")) {
                val release = CompletableDeferred<Int>()
                var stopped = false
                val world = world(type) {
                    try {
                        release.await()
                    } finally {
                        stopped = true
                    }
                }
                val query = if (type == "Mutation") "mutation { value }" else "{ value }"
                val response = graphql(world, StandardTestDispatcher(testScheduler))
                    .executeAsync(ExecutionInput.newExecutionInput().query(query))
                try {
                    runCurrent()
                    advanceTimeBy(120_000)
                    runCurrent()
                    assertFalse(response.isDone)
                    assertFalse(stopped)
                    release.complete(7)
                    runCurrent()
                    assertEquals(mapOf("value" to 7), response.join().getData())
                    assertTrue(response.join().errors.isEmpty())
                    assertTrue(stopped)
                } finally {
                    release.complete(7)
                    response.cancel(true)
                    runCurrent()
                }
            }
        }

    @Test
    fun `query and mutation cycles fail eagerly without a deadline`() =
        runTest {
            for (type in listOf("Query", "Mutation")) {
                var cycleFailure: ResolverReadCycleException? = null
                val world = TestWorld.fromSDL(
                    "type Query { value: Int } type Mutation { value: Int }",
                    fieldResolvers = { schema ->
                        mapOf(
                            schema.loweredSchema.requireObjectField(type, "value") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf(type)) { _, _, context ->
                                // A genuine self-wait, injected below the tenant API to bypass registry validation.
                                val task = assertIs<FieldResolverTask>(context)
                                val publication = task.publication
                                val reader = publication.oerOccurrence.root.fieldResolverCycleTask(publication.sourceOccurrence.publicationPath)
                                try {
                                    publication.operation.cycleChecker.cycleCheck(reader, publication.publicationCell.valueCycleSlot)
                                } catch (failure: ResolverReadCycleException) {
                                    cycleFailure = failure
                                    throw failure
                                }
                                publication.publicationCell.value.await()
                            }
                        )
                    },
                )
                val query = if (type == "Mutation") "mutation { value }" else "{ value }"
                val response = graphql(world, StandardTestDispatcher(testScheduler))
                    .executeAsync(ExecutionInput.newExecutionInput().query(query))
                try {
                    runCurrent()
                    assertTrue(response.isDone)
                    assertEquals(mapOf("value" to null), response.join().getData())
                    assertEquals(listOf("value"), response.join().errors.single().path)
                    val failure = assertIs<ResolverReadCycleException>(cycleFailure)
                    assertNull(failure.cause)
                    assertEquals(2, failure.cycle.size)
                    assertEquals(failure.cycle.first(), failure.cycle.last())
                    assertEquals(0L, testScheduler.currentTime)
                } finally {
                    response.cancel(true)
                    runCurrent()
                }
            }
        }

    @Test
    fun `successful completion leaves no request timer scheduled`() =
        runTest {
            val result = graphql(world("Query") { 7 }, StandardTestDispatcher(testScheduler)).executeAsync(ExecutionInput.newExecutionInput().query("{ value }"))
            runCurrent()
            assertEquals(mapOf("value" to 7), result.join().getData())
            advanceUntilIdle()
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `caller cancellation still cancels a suspended resolver`() =
        runTest {
            val caller = QPlanCallerCancellation()
            var stopped = false
            val result = graphql(
                world("Query") {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped = true
                    }
                },
                StandardTestDispatcher(testScheduler),
            ).executeAsync(ExecutionInput.newExecutionInput().query("{ value }").graphQLContext { it.put(QPlanCallerCancellationKey, caller) })
            runCurrent()
            assertTrue(result.cancel(true))
            caller.cancel(requestCancellation("Caller cancelled"))
            advanceUntilIdle()
            assertTrue(stopped)
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `incremental work survives the former deadline and withheld demand`() =
        runTest {
            val release = CompletableDeferred<Int>()
            var stopped = false
            val world = TestWorld.fromSDL(
                "type Query { fast: Int slow: Int }",
                fieldResolvers = { schema ->
                    val empty = schema.loweredSchema.emptyFragmentOf("Query")
                    mapOf(
                        schema.loweredSchema.requireObjectField("Query", "fast") to fieldResolverOf(empty) { _, _ -> 7 },
                        schema.loweredSchema.requireObjectField("Query", "slow") to fieldResolverOf(empty) { _, _ ->
                            try {
                                release.await()
                            } finally {
                                stopped = true
                            }
                        },
                    )
                },
            )
            val input = ExecutionInput.newExecutionInput().query("{ fast ... @defer { slow } }")
            GraphQL.unusualConfiguration(input).incrementalSupport().enableIncrementalSupport(true).enableEarlyIncrementalFieldExecution(true)
            val response = graphql(world, StandardTestDispatcher(testScheduler)).executeAsync(input.build())
            val next = CompletableFuture<DelayedIncrementalPartialResult>()
            val terminal = CompletableFuture<Unit>()
            val subscription = CompletableFuture<Subscription>()
            try {
                runCurrent()
                val initial = assertIs<IncrementalExecutionResult>(response.join())
                assertEquals(mapOf("fast" to 7), initial.getData())
                initial.incrementalItemPublisher.subscribe(object : Subscriber<DelayedIncrementalPartialResult> {
                    override fun onSubscribe(value: Subscription) {
                        subscription.complete(value)
                    }

                    override fun onNext(item: DelayedIncrementalPartialResult) {
                        next.complete(item)
                    }

                    override fun onError(throwable: Throwable) {
                        next.completeExceptionally(throwable)
                        terminal.completeExceptionally(throwable)
                    }

                    override fun onComplete() {
                        terminal.complete(Unit)
                    }
                })
                advanceTimeBy(120_000)
                runCurrent()
                assertFalse(stopped)
                assertFalse(next.isDone)
                assertFalse(terminal.isDone)
                release.complete(9)
                runCurrent()
                assertTrue(stopped)
                assertFalse(next.isDone)
                subscription.join().request(Long.MAX_VALUE)
                runCurrent()
                assertTrue(next.isDone)
                assertTrue(terminal.isDone)
                val payload = assertIs<DeferPayload>(next.join().incremental.single())
                assertEquals(mapOf("slow" to 9), payload.getData())
                assertTrue(payload.errors.isEmpty())
                assertFalse(next.join().hasNext())
                terminal.join()
            } finally {
                release.complete(9)
                if (subscription.isDone) subscription.join().cancel()
                response.cancel(true)
                runCurrent()
            }
        }

    private fun world(
        type: String,
        callback: suspend () -> Int
    ): TestWorld =
        TestWorld.fromSDL(
            "type Query { value: Int } type Mutation { value: Int }",
            fieldResolvers = { schema ->
                mapOf(schema.loweredSchema.requireObjectField(type, "value") to fieldResolverOf(schema.loweredSchema.emptyFragmentOf(type)) { _, _ -> callback() })
            },
        )

    private fun graphql(
        world: TestWorld,
        context: CoroutineContext,
    ): GraphQL {
        val strategy = QPlanExecutionStrategy(world.assumptions, world.schemas.graphQLSchema, context)
        val schema = QPlanWiringFactory(world.schema).wire(world.schemas.graphQLSchema)
            .transform { it.additionalDirective(Directives.DeferDirective) }
        return GraphQL.newGraphQL(schema).queryExecutionStrategy(strategy).mutationExecutionStrategy(strategy).instrumentation(QPlanInstrumentation()).build()
    }
}
