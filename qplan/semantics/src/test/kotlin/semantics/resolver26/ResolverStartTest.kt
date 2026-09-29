@file:Suppress("ForbiddenImport")

package semantics.resolver26

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import model.ObjectEngineResult
import model.ErrorEngineResult
import model.ResolverOccurrenceId
import model.emptyFragmentOf
import model.fragmentFrom
import model.operationSelectionsFrom
import model.requireField
import model.requireObjectField
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.contract.get
import semantics.shared.CycleCheckState
import semantics.shared.fieldResolverCycleTask
import semantics.shared.valueCycleSlot
import semantics.shared.ResolverObserver
import semantics.shared.ResolverInvocationObservation
import semantics.shared.ResolverReadCycleException
import semantics.shared.SharedOperationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResolverStartTest : Resolver26DispatcherResource {
    @Test
    fun `nested execution retains observer but only query fragment emits preparation`() {
        val observer = InvocationRecordingObserver()
        val testWorld = TestWorld.fromSDL(
            schemaSDL = "type Query { outer: Int inner: Int dependency: Int }",
            fieldResolvers = { schema ->
                val empty = schema.emptyFragmentOf("Query")
                val nested = schema.fragmentFrom("fragment Nested on Query { inner }")
                mapOf(
                    schema.requireObjectField("Query", "outer") to fieldResolverOf(empty) { _, _, executionContext ->
                        executionContext.resolveSelectionSet(nested.materializeSelections).get("inner")
                    },
                    schema.requireObjectField("Query", "inner") to fieldResolverOf(
                        empty, schema.fragmentFrom("fragment Input on Query { dependency }"),
                    ) { _, query, _ -> query.get("dependency") },
                    schema.requireObjectField("Query", "dependency") to fieldResolverOf(empty) { _, _ -> 7 },
                )
            },
        )
        val operation = SharedOperationContext.create(testWorld.assumptions, resolverObserver = observer)
        val root = operation.resolveWithTestDispatcher(
            operation.world.operationSelectionsFrom("{ outer }"),
        )
        val byField = observer.events.associateBy { it.field.name }
        assertEquals(setOf("outer", "inner", "dependency"), byField.keys)
        assertEquals(3, observer.events.size)
        val outer = byField.getValue("outer").resolverOccurrenceId
        val inner = byField.getValue("inner").resolverOccurrenceId
        val dependency = byField.getValue("dependency").resolverOccurrenceId
        assertEquals(ResolverOccurrenceId.at(root, byField.getValue("outer").occurrencePath), outer)
        assertNotEquals(ResolverOccurrenceId.at(root, byField.getValue("inner").occurrencePath), inner)
        assertEquals(setOf(inner), observer.allQueryFragmentResults().keys)
        val queryRoot = observer.queryFragmentResults(inner).single()
        assertEquals(ResolverOccurrenceId.at(queryRoot, byField.getValue("dependency").occurrencePath), dependency)
        assertEquals(7, root.getCell(root.keys.single()).get())
    }

    @Test
    fun `successful nested query retains its caller dependency in the operation checker`(): Unit =
        runBlocking {
            lateinit var observedChecker: CycleCheckState
            lateinit var innerRoot: ObjectEngineResult
            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL = "type Query { outer: Int!, inner: Int! }",
                    fieldResolvers = { schema ->
                        val outer = schema.requireObjectField("Query", "outer")
                        val inner = schema.requireObjectField("Query", "inner")
                        val nested = schema.fragmentFrom("fragment Nested on Query { inner }")
                        mapOf(
                            outer to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, executionContext ->
                                    observedChecker =
                                        assertIs<FieldResolverTask>(executionContext)
                                            .publication
                                            .operation
                                            .cycleChecker
                                    executionContext
                                        .resolveSelectionSet(nested.materializeSelections)
                                        .get("inner")
                                },
                            inner to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, executionContext ->
                                    innerRoot =
                                        assertIs<FieldResolverTask>(executionContext)
                                            .publication
                                            .oerOccurrence
                                            .root
                                    7
                                },
                        )
                    },
                )
            val operation = SharedOperationContext.create(testWorld.assumptions)
            val result =
                operation.resolveWithTestDispatcher(
                    operation.world.operationSelectionsFrom("query { outer }"),
                )
            val outerKey =
                ObjectEngineResult.GroundKey.of(
                    operation.world.schema.requireObjectField("Query", "outer"),
                    emptyMap(),
                )
            val innerKey =
                ObjectEngineResult.GroundKey.of(
                    operation.world.schema.requireObjectField("Query", "inner"),
                    emptyMap(),
                )
            val outerCell = result.getCell(outerKey)
            assertEquals(7, outerCell.value.await())

            assertFailsWith<ResolverReadCycleException> {
                observedChecker.cycleCheck(
                    reader = innerRoot.fieldResolverCycleTask(listOf(innerKey)),
                    slot = outerCell.valueCycleSlot,
                )
            }
        }

    @Test
    fun `equal resolver paths in distinct nested Query roots do not form a cycle`(): Unit =
        runBlocking {
            val invocations = AtomicInteger()
            val testWorld =
                TestWorld.fromSDL(
                    schemaSDL = "type Query { recursive: Int! }",
                    fieldResolvers = { schema ->
                        val field = schema.requireObjectField("Query", "recursive")
                        val nested =
                            schema.fragmentFrom("fragment Nested on Query { recursive }")
                        mapOf(
                            field to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, executionContext ->
                                    if (invocations.incrementAndGet() > 1) {
                                        7
                                    } else {
                                        executionContext
                                            .resolveSelectionSet(nested.materializeSelections)
                                            .get("recursive")
                                    }
                                },
                        )
                    },
                )
            val operation = SharedOperationContext.create(testWorld.assumptions)
            val result =
                operation.resolveWithTestDispatcher(
                    operation.world.operationSelectionsFrom("query { recursive }"),
                )
            val key =
                ObjectEngineResult.GroundKey.of(
                    operation.world.schema.requireObjectField("Query", "recursive"),
                    emptyMap(),
                )

            assertEquals(2, invocations.get())
            assertEquals(7, result.getCell(key).value.await())
        }

    @Test
    fun `request cancellation records entered producer but not waiting consumer`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val observer = InvocationRecordingObserver()
        val testWorld = TestWorld.fromSDL(
            schemaSDL = "type Query { consumer: Int slow: Int }",
            fieldResolvers = { schema ->
                mapOf(
                    schema.requireObjectField("Query", "consumer") to fieldResolverOf(
                        schema.fragmentFrom("fragment Input on Query { slow }"),
                    ) { _, _ -> error("consumer must not enter") },
                    schema.requireObjectField("Query", "slow") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    },
                )
            },
        )
        val job = Job()
        try {
            val operation = SharedOperationContext.create(testWorld.assumptions, resolverObserver = observer)
            operation.startResolve(
                operation.world.operationSelectionsFrom("{ consumer }"),
                CoroutineScope(resolverDispatcher + job),
            )
            withTimeout(5000) { entered.await() }
            job.cancelAndJoin()
            withTimeout(5000) { stopped.await() }
            assertEquals(listOf("slow"), observer.events.map { it.field.name })
            assertEquals(observer.events.map { it.resolverOccurrenceId }.toSet(), observer.invokedResolverOccurrences())
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun `nested selection execution is owned by the calling field task`() =
        runBlocking {
            val nestedStarted = CompletableDeferred<Unit>()
            val nestedStopped = CompletableDeferred<Unit>()
            val outerJob = CompletableDeferred<Job>()
            val gate = CompletableDeferred<Unit>()
            val world =
                TestWorld.fromSDL(
                    schemaSDL =
                        """
                        type Query {
                          outer: Int!
                          slow: Int!
                        }
                        """.trimIndent(),
                    fieldResolvers = { schema ->
                        val outer = schema.requireObjectField("Query", "outer")
                        val slow = schema.requireObjectField("Query", "slow")
                        val nestedFragment =
                            schema.fragmentFrom("fragment Nested on Query { slow }")
                        mapOf(
                            outer to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _, executionContext ->
                                    val task = assertIs<FieldResolverTask>(executionContext)
                                    val publication = task.publication
                                    val operation: OperationContext = publication
                                    assertSame(task.publication.operation.dispatcher, operation.dispatcher)
                                    val childOperation = operation.forChildScope(task.fieldTaskScope)
                                    assertNotSame(operation.dispatcher, childOperation.dispatcher)
                                    assertSame(task.publication.operation.world, childOperation.world)
                                    assertSame(
                                        task.publication.operation.variableBindings,
                                        childOperation.variableBindings,
                                    )
                                    assertSame(
                                        task.publication.operation.resolverObserver,
                                        childOperation.resolverObserver,
                                    )
                                    assertSame(
                                        task.publication.operation.cycleChecker,
                                        childOperation.cycleChecker,
                                    )
                                    assertSame(
                                        task.publication.operation.bindingsState,
                                        childOperation.bindingsState,
                                    )
                                    outerJob.complete(currentCoroutineContext().job)
                                    executionContext
                                        .resolveSelectionSet(nestedFragment.materializeSelections)
                                        .get("slow")
                                },
                            slow to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                    nestedStarted.complete(Unit)
                                    try {
                                        gate.await()
                                        2
                                    } finally {
                                        nestedStopped.complete(Unit)
                                    }
                                },
                        )
                    },
                )
            val selections = world.assumptions.operationSelectionsFrom("query { outer }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolverDispatcher + requestJob)

            try {
                val root =
                    SharedOperationContext.create(world.assumptions).startResolve(selections, requestScope)
                withTimeout(5_000) { nestedStarted.await() }
                withTimeout(5_000) {
                    outerJob.await().cancelAndJoin()
                }

                withTimeout(5_000) { nestedStopped.await() }
                assertIs<CancellationException>(root.cell("outer").value.awaitFailure())
                assertTrue(requestJob.isActive)
                assertFalse(gate.isCompleted)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `startResolve publishes root shape before resolver values complete`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val world = delayedWorld(providerStarted, gate)
            val selections = world.assumptions.operationSelectionsFrom("query { fast slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolverDispatcher + requestJob)

            try {
                val root =
                    SharedOperationContext.create(world.assumptions).startResolve(selections, requestScope)
                val fast = root.cell("fast")
                val slow = root.cell("slow")

                assertFalse(slow.value.isCompleted)
                withTimeout(5_000) { providerStarted.await() }
                assertEquals(1, withTimeout(5_000) { fast.value.await() })
                assertFalse(slow.value.isCompleted)

                gate.complete(Unit)
                assertEquals(2, withTimeout(5_000) { slow.value.await() })
                assertTrue(slow.value.isCompleted)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `cancelling the request scope cancels resolver work`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val providerStopped = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val world = delayedWorld(providerStarted, gate, providerStopped)
            val selections = world.assumptions.operationSelectionsFrom("query { slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolverDispatcher + requestJob)

            val root =
                SharedOperationContext.create(world.assumptions).startResolve(selections, requestScope)
            withTimeout(5_000) { providerStarted.await() }

            requestJob.cancelAndJoin()

            withTimeout(5_000) { providerStopped.await() }
            assertIs<CancellationException>(root.cell("slow").value.awaitFailure())
            assertFalse(gate.isCompleted)
        }

    @Test
    fun `task-local cancellation becomes the affected field error`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val world =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val slow = schema.requireObjectField("Query", "slow")
                        mapOf(
                            slow to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Slow on Query { " +
                                            "dependency @include(if: ${'$'}ready) }",
                                        variableField = slow,
                                    ),
                                ) { _, _ -> 2 }
                                    .withVariablesProvider(setOf("ready")) {
                                        providerStarted.complete(Unit)
                                        throw CancellationException("provider cancelled")
                                    },
                        )
                    },
                )
            val selections = world.assumptions.operationSelectionsFrom("query { slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolverDispatcher + requestJob)

            try {
                val root =
                    SharedOperationContext.create(world.assumptions).startResolve(selections, requestScope)
                val slow = root.cell("slow")

                withTimeout(5_000) { providerStarted.await() }
                assertTrue(requestJob.isActive)
                val error = assertIs<ErrorEngineResult>(slow.value.await())

                assertTrue(slow.value.isCompleted)
                assertEquals(
                    "provider cancelled",
                    assertIs<CancellationException>(error.errorData.cause).message,
                )
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `task-local cancellation becomes field errors without cancelling the request`() =
        runBlocking {
            val providerStarted = CompletableDeferred<Unit>()
            val world =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val slow = schema.requireObjectField("Query", "slow")
                        val dependent = schema.requireObjectField("Query", "dependent")
                        mapOf(
                            slow to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Slow on Query { " +
                                            "dependency @include(if: ${'$'}ready) }",
                                        variableField = slow,
                                    ),
                                ) { _, _ -> 2 }
                                    .withVariablesProvider(setOf("ready")) {
                                        providerStarted.complete(Unit)
                                        throw CancellationException("provider cancelled")
                                    },
                            dependent to
                                fieldResolverOf(
                                    schema.fragmentFrom("fragment Dependent on Query { slow }"),
                                ) { _, _ -> error("dependent resolver should not execute") },
                        )
                    },
                )
            val selections = world.assumptions.operationSelectionsFrom("query { dependent }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolverDispatcher + requestJob)

            try {
                val root =
                    SharedOperationContext.create(world.assumptions).startResolve(selections, requestScope)

                withTimeout(5_000) { providerStarted.await() }
                val slowError =
                    assertIs<ErrorEngineResult>(
                        withTimeout(5_000) { root.cell("slow").value.await() },
                    )
                assertIs<CancellationException>(slowError.errorData.cause)
                assertIs<ErrorEngineResult>(
                    withTimeout(5_000) { root.cell("dependent").value.await() },
                )
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `provider contract exception becomes a field error`() =
        runBlocking {
            val world =
                TestWorld.fromSDL(
                    schemaSDL = SCHEMA,
                    fieldResolvers = { schema ->
                        val slow = schema.requireObjectField("Query", "slow")
                        mapOf(
                            slow to
                                fieldResolverOf(
                                    schema.fragmentFrom(
                                        "fragment Slow on Query { " +
                                            "dependency @include(if: ${'$'}ready) }",
                                        variableField = slow,
                                    ),
                                ) { _, _ -> 2 }
                                    .withVariablesProvider(setOf("ready")) { emptyMap() },
                        )
                    },
                )
            val selections = world.assumptions.operationSelectionsFrom("query { slow }")
            val requestJob = Job()
            val requestScope = CoroutineScope(resolverDispatcher + requestJob)

            try {
                val root =
                    SharedOperationContext.create(world.assumptions).startResolve(selections, requestScope)
                val error =
                    assertIs<ErrorEngineResult>(
                        withTimeout(5_000) { root.cell("slow").value.await() },
                    )
                val publicationFailure = assertIs<IllegalStateException>(error.errorData.cause)

                assertTrue(
                    publicationFailure.message.orEmpty().contains(
                        "VariablesProvider returned invalid variables. Missing keys: ready",
                    ),
                )
                assertTrue(requestJob.isActive)
            } finally {
                requestJob.cancelAndJoin()
            }
        }

    @Test
    fun `field exception does not cancel queued sibling producers`() =
        runBlocking {
            ResolutionDispatcherFactory.create(1).use { dispatcher ->
                val siblingStarted = CompletableDeferred<Unit>()
                val world =
                    TestWorld.fromSDL(
                        schemaSDL = SCHEMA,
                        fieldResolvers = { schema ->
                            val fast = schema.requireObjectField("Query", "fast")
                            val slow = schema.requireObjectField("Query", "slow")
                            mapOf(
                                fast to
                                    fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                        throw NullPointerException("tenant bug")
                                    },
                                slow to
                                    fieldResolverOf(
                                        schema.fragmentFrom(
                                            "fragment Slow on Query { " +
                                                "dependency @include(if: ${'$'}ready) }",
                                            variableField = slow,
                                        ),
                                    ) { _, _ -> 2 }
                                        .withVariablesProvider(setOf("ready")) {
                                            siblingStarted.complete(Unit)
                                            mapOf("ready" to false)
                                        },
                            )
                        },
                    )
                val selections =
                    world.assumptions.operationSelectionsFrom("query { fast slow }")
                val requestJob = Job()
                val requestScope = CoroutineScope(dispatcher + requestJob)

                try {
                    val root =
                        SharedOperationContext.create(world.assumptions).startResolve(selections, requestScope)
                    val fastError =
                        assertIs<ErrorEngineResult>(
                            withTimeout(5_000) { root.cell("fast").value.await() },
                        )
                    val slowValue = withTimeout(5_000) { root.cell("slow").value.await() }

                    assertEquals(
                        "tenant bug",
                        assertIs<NullPointerException>(fastError.errorData.cause).message,
                    )
                    assertEquals(2, slowValue)
                    withTimeout(5_000) { siblingStarted.await() }
                    assertTrue(requestJob.isActive)
                } finally {
                    requestJob.cancelAndJoin()
                }
            }
        }

    @Test
    fun `request cancellation terminates field promises before coroutine entry`() =
        runBlocking {
            ResolutionDispatcherFactory.create(1).use { dispatcher ->
                val blockerStarted = CountDownLatch(1)
                val releaseBlocker = CountDownLatch(1)
                val resolverStarted = AtomicBoolean()
                dispatcher.executor.execute {
                    blockerStarted.countDown()
                    releaseBlocker.await()
                }
                assertTrue(blockerStarted.await(5, TimeUnit.SECONDS))

                val world =
                    TestWorld.fromSDL(
                        schemaSDL = SCHEMA,
                        fieldResolvers = { schema ->
                            val fast = schema.requireObjectField("Query", "fast")
                            mapOf(
                                fast to
                                    fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                        resolverStarted.set(true)
                                        1
                                    },
                            )
                        },
                    )
                val requestJob = Job()
                val requestScope = CoroutineScope(dispatcher + requestJob)

                try {
                    val root =
                        SharedOperationContext.create(world.assumptions).startResolve(
                            world.assumptions.operationSelectionsFrom("query { fast }"),
                            requestScope,
                        )
                    requestJob.cancel(CancellationException("cancelled before dispatch"))
                    releaseBlocker.countDown()

                    val cancellation =
                        withTimeout(5_000) { root.cell("fast").value.awaitFailure() }
                    assertTrue(cancellation.message.orEmpty().contains("cancelled before dispatch"))
                    assertIs<CancellationException>(cancellation)
                    assertFalse(resolverStarted.get())
                } finally {
                    releaseBlocker.countDown()
                    requestJob.cancelAndJoin()
                }
            }
        }

    private fun delayedWorld(
        providerStarted: CompletableDeferred<Unit>,
        gate: CompletableDeferred<Unit>,
        providerStopped: CompletableDeferred<Unit>? = null,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL = SCHEMA,
            fieldResolvers = { schema ->
                val fast = schema.requireObjectField("Query", "fast")
                val slow = schema.requireObjectField("Query", "slow")
                mapOf(
                    fast to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 },
                    slow to
                        fieldResolverOf(
                            schema.fragmentFrom(
                                "fragment Slow on Query { " +
                                    "dependency @include(if: ${'$'}ready) }",
                                variableField = slow,
                            ),
                        ) { _, _ -> 2 }
                            .withVariablesProvider(setOf("ready")) {
                                providerStarted.complete(Unit)
                                try {
                                    gate.await()
                                    mapOf("ready" to false)
                                } finally {
                                    providerStopped?.complete(Unit)
                                }
                            },
                )
            },
        )

    private fun ObjectEngineResult.cell(fieldName: String) =
        getCell(
            ObjectEngineResult.GroundKey.of(
                type.requireField(fieldName),
                emptyMap(),
            ),
        )

    private suspend fun <T> model.Promise<T>.awaitFailure(): Exception =
        try {
            await()
            error("Promise completed successfully")
        } catch (failure: Exception) {
            failure
        }

    private class InvocationRecordingObserver : ResolverObserver {
        private val invokedOccurrences = ConcurrentHashMap.newKeySet<ResolverOccurrenceId>()
        private val queryResults = ConcurrentHashMap<ResolverOccurrenceId, ConcurrentLinkedQueue<ObjectEngineResult>>()
        val events = CopyOnWriteArrayList<ResolverInvocationObservation>()

        override fun onResolverInvocation(observation: ResolverInvocationObservation) {
            invokedOccurrences += observation.resolverOccurrenceId
            events += observation
        }

        override fun onQueryFragmentPrepared(resolverOccurrenceId: ResolverOccurrenceId, result: ObjectEngineResult) {
            queryResults.computeIfAbsent(resolverOccurrenceId) { ConcurrentLinkedQueue() }.add(result)
        }

        fun invokedResolverOccurrences(): Set<ResolverOccurrenceId> = invokedOccurrences.toSet()

        fun queryFragmentResults(resolverOccurrenceId: ResolverOccurrenceId): List<ObjectEngineResult> =
            queryResults[resolverOccurrenceId]?.toList().orEmpty()

        fun allQueryFragmentResults(): Map<ResolverOccurrenceId, List<ObjectEngineResult>> =
            queryResults.mapValues { (_, results) -> results.toList() }
    }

    private companion object {
        val SCHEMA =
            """
            type Query {
              fast: Int!
              slow: Int!
              dependent: Int!
              dependency: Int!
            }
            """.trimIndent()
    }
}
