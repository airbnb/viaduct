@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.ResultPath
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CompletedEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.IncrementalExecutionResult.Update
import viaduct.engine.api.PendingEntry
import viaduct.engine.api.instrumentation.IncrementalExecutionInstrumentationContext
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.context.DispatcherLocalContext
import viaduct.engine.runtime.context.updateCompositeLocalContext
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createExecutionInput
import viaduct.engine.runtime.execution.ExecutionTestHelpers.createSchema
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@Timeout(10)
class ExecutionSessionTest {
    private fun observedSession(context: IncrementalExecutionInstrumentationContext) = ExecutionSession { context }

    private fun observer(
        initial: (ExecutionResult) -> Unit,
        update: (Update) -> Unit,
        completed: (Throwable?) -> Unit,
    ) = object : IncrementalExecutionInstrumentationContext {
        override fun onInitialResult(result: ExecutionResult) = initial(result)

        override fun onUpdate(update: Update) = update(update)

        override fun onCompleted(throwable: Throwable?) = completed(throwable)
    }

    @Test
    fun `WrappedCoroutineExecutionStrategy reports each incremental callback once and uses the configured dispatcher`(): Unit =
        runExecutionTest<Unit> {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val expectedThread = withContext(dispatcher) { Thread.currentThread() }
                val release = CompletableDeferred<Unit>()
                val completed = CompletableDeferred<Throwable?>()
                val events = mutableListOf<String>()
                val threads = mutableListOf<Thread>()
                val expectedUpdate = update()
                val original = initial(
                    flow {
                        release.await()
                        threads.add(Thread.currentThread())
                        emit(expectedUpdate)
                    },
                ) { events.add("cleanup") }
                val state = object : InstrumentationState {}
                var observedState: InstrumentationState? = null
                var observedParameters: InstrumentationExecutionParameters? = null
                val requestInstrumentation = object : ViaductModernGJInstrumentation {
                    override fun beginIncrementalExecution(
                        parameters: InstrumentationExecutionParameters,
                        state: InstrumentationState?
                    ): IncrementalExecutionInstrumentationContext {
                        observedParameters = parameters
                        observedState = state
                        events.add("begin")
                        threads.add(Thread.currentThread())
                        return observer(
                            { events.add("initial") },
                            { events.add("update") },
                            {
                                events.add("complete")
                                completed.complete(it)
                            },
                        )
                    }
                }
                val schema = createSchema("type Query { value: Int }", emptyMap())
                val input = createExecutionInput(
                    schema,
                    "{ value }",
                    incrementalExecution = true,
                ).let { input ->
                    input.transform { builder ->
                        builder.localContext(input.updateCompositeLocalContext<DispatcherLocalContext> { DispatcherLocalContext(dispatcher) })
                    }
                }
                val context = mockk<ExecutionContext> {
                    every { executionInput } returns input
                    every { graphQLSchema } returns schema.schema
                    every { instrumentation } returns requestInstrumentation
                    every { instrumentationState } returns state
                }
                val parameters = mockk<ExecutionStrategyParameters> {
                    every { parent } returns null
                }
                val delegate = mockk<ExecutionStrategy> {
                    every { execute(context, parameters) } answers {
                        events.add("execute")
                        threads.add(Thread.currentThread())
                        CompletableFuture.completedFuture(original)
                    }
                }
                val future = WrappedCoroutineExecutionStrategy(delegate, DefaultCoroutineInterop).execute(context, parameters)
                val result = future.await() as IncrementalExecutionResult
                try {
                    assertSame(input, observedParameters?.executionInput)
                    assertSame(state, observedState)
                    events.shouldContainExactly("begin", "execute", "initial")
                    assertFalse(completed.isCompleted)

                    release.complete(Unit)

                    assertNull(completed.await())
                    result.updates.toList().shouldContainExactly(expectedUpdate)
                    events.shouldContainExactly("begin", "execute", "initial", "update", "cleanup", "complete")
                    threads.shouldContainExactly(expectedThread, expectedThread, expectedThread)
                } finally {
                    release.complete(Unit)
                    result.cancel()
                    future.cancel(true)
                }
            }
        }

    @Test
    fun `WrappedCoroutineExecutionStrategy delivers incremental results without incremental instrumentation`(): Unit =
        runExecutionTest<Unit> {
            val expectedUpdate = update()
            val original = initial(flow { emit(expectedUpdate) })
            val schema = createSchema("type Query { value: Int }", emptyMap())
            val input = createExecutionInput(
                schema,
                "{ value }",
                incrementalExecution = true,
            )
            val context = mockk<ExecutionContext> {
                every { executionInput } returns input
                every { instrumentation } returns SimplePerformantInstrumentation.INSTANCE
            }
            val parameters = mockk<ExecutionStrategyParameters> {
                every { parent } returns null
            }
            val delegate = mockk<ExecutionStrategy> {
                every { execute(context, parameters) } returns CompletableFuture.completedFuture(original)
            }

            val result = WrappedCoroutineExecutionStrategy(delegate, DefaultCoroutineInterop).execute(context, parameters).await() as IncrementalExecutionResult
            try {
                assertEquals(original.pending, result.pending)
                assertEquals(original.getData<Any?>(), result.getData<Any?>())
                result.updates.toList().shouldContainExactly(expectedUpdate)
            } finally {
                result.cancel()
            }
        }

    @Test
    fun `WrappedCoroutineExecutionStrategy skips the incremental hook when incremental execution is disabled`(): Unit =
        runExecutionTest<Unit> {
            var hookCalls = 0
            val requestInstrumentation = object : ViaductModernGJInstrumentation {
                override fun beginIncrementalExecution(
                    parameters: InstrumentationExecutionParameters,
                    state: InstrumentationState?
                ): IncrementalExecutionInstrumentationContext? {
                    hookCalls++
                    return null
                }
            }
            val schema = createSchema("type Query { value: Int }", emptyMap())
            val input = createExecutionInput(schema, "{ value }", incrementalExecution = false)
            val context = mockk<ExecutionContext> {
                every { executionInput } returns input
                every { graphQLSchema } returns schema.schema
                every { instrumentation } returns requestInstrumentation
                every { instrumentationState } returns null
            }
            val parameters = mockk<ExecutionStrategyParameters> {
                every { parent } returns null
            }
            val original = ExecutionResult.newExecutionResult().data(null).build()
            val delegate = mockk<ExecutionStrategy> {
                every { execute(context, parameters) } returns CompletableFuture.completedFuture(original)
            }

            val result = WrappedCoroutineExecutionStrategy(delegate, DefaultCoroutineInterop).execute(context, parameters).await()

            assertSame(original, result)
            assertEquals(0, hookCalls)
        }

    @Test
    fun `a failure to create the observer does not prevent incremental results from being delivered`(): Unit =
        runExecutionTest<Unit> {
            val session = ExecutionSession { error("instrumentation setup failed") }
            session.start(DefaultCoroutineInterop) { initial(flow { emit(update()) }) }

            val result = session.initialResult.await() as IncrementalExecutionResult
            session.completion.await()

            assertEquals(1, result.updates.toList().size)
        }

    @Test
    fun `incremental completion is reported after cleanup even if the caller has not read updates`(): Unit =
        runExecutionTest<Unit> {
            val events = mutableListOf<String>()
            val release = CompletableDeferred<Unit>()
            val observer = observer(
                { events.add("initial") },
                { events.add("update") },
                { failure ->
                    assertEquals(null, failure)
                    events.add("complete")
                },
            )
            val session = ExecutionSession { observer }
            session.start(DefaultCoroutineInterop) {
                initial(
                    flow {
                        release.await()
                        emit(update())
                    }
                ) { events.add("cleanup") }
            }
            val result = session.initialResult.await() as IncrementalExecutionResult
            events.shouldContainExactly("initial")

            release.complete(Unit)
            session.completion.await()

            events.shouldContainExactly("initial", "update", "cleanup", "complete")
            assertEquals(1, result.updates.toList().size)
            events.shouldContainExactly("initial", "update", "cleanup", "complete")
        }

    @Test
    fun `ordinary responses call instrumentation before execution and report the initial result and completion`(): Unit =
        runExecutionTest<Unit> {
            val events = mutableListOf<String>()
            val original = ExecutionResult.newExecutionResult().data(null).build()
            var observedResult: ExecutionResult? = null
            val session = ExecutionSession {
                events.add("begin")
                observer(
                    {
                        observedResult = it
                        events.add("initial")
                    },
                    { events.add("update") },
                    {
                        assertNull(it)
                        events.add("complete")
                    },
                )
            }
            session.start(DefaultCoroutineInterop) {
                events.add("execute")
                original
            }

            assertSame(original, session.initialResult.await())
            session.completion.await()

            assertSame(original, observedResult)
            events.shouldContainExactly("begin", "execute", "initial", "complete")
        }

    @Test
    fun `execution failures before the initial result still complete instrumentation`(): Unit =
        runExecutionTest<Unit> {
            val events = mutableListOf<String>()
            val failures = mutableListOf<Throwable?>()
            val session = ExecutionSession {
                events.add("begin")
                observer(
                    { events.add("initial") },
                    { events.add("update") },
                    {
                        failures.add(it)
                        events.add("complete")
                    },
                )
            }
            session.start(DefaultCoroutineInterop) {
                events.add("execute")
                error("initial execution failed")
            }

            assertThrows<IllegalStateException> { session.initialResult.await() }
            assertThrows<IllegalStateException> { session.completion.await() }

            events.shouldContainExactly("begin", "execute", "complete")
            assertEquals(1, failures.size)
            assertEquals("initial execution failed", failures.single()?.message)
        }

    @Test
    fun `incremental failure and cancellation each invoke onCompleted once`(): Unit =
        runExecutionTest<Unit> {
            for (cancel in listOf(false, true)) {
                val release = CompletableDeferred<Unit>()
                val failures = mutableListOf<Throwable?>()
                val session = observedSession(observer({}, {}, { failures.add(it) }))
                session.start(DefaultCoroutineInterop) {
                    initial(
                        flow {
                            release.await()
                            error("deferred failed")
                        }
                    )
                }
                val result = session.initialResult.await() as IncrementalExecutionResult

                if (cancel) result.cancel() else release.complete(Unit)
                assertThrows<Exception> { session.completion.await() }
                result.cancel()

                assertEquals(1, failures.size)
                if (cancel) {
                    assertInstanceOf(CancellationException::class.java, failures.single())
                } else {
                    assertEquals("deferred failed", failures.single()?.message)
                }
            }
        }

    @Test
    fun `observer callback failures do not prevent initial results or updates from being delivered`(): Unit =
        runExecutionTest<Unit> {
            var callbacks = 0
            val session = observedSession(
                observer(
                    {
                        callbacks++
                        error("initial observer failed")
                    },
                    {
                        callbacks++
                        error("update observer failed")
                    },
                    {
                        callbacks++
                        error("completion observer failed")
                    },
                )
            )
            session.start(DefaultCoroutineInterop) { initial(flow { emit(update()) }) }

            val result = session.initialResult.await() as IncrementalExecutionResult
            session.completion.await()

            assertEquals(1, result.updates.toList().size)
            assertEquals(3, callbacks)
        }

    @Test
    fun `complete responses are returned after request cleanup`(): Unit =
        runExecutionTest<Unit> {
            for (dataPresent in listOf(false, true)) {
                val original = ExecutionResult.newExecutionResult().apply { if (dataPresent) data(null) }.build()
                val started = CompletableDeferred<Unit>()
                val cancelled = CompletableDeferred<Unit>()
                val session = ExecutionSession()
                session.start(DefaultCoroutineInterop) { supervisorFactory ->
                    supervisorFactory(currentCoroutineContext()).launch {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                    started.await()
                    original
                }

                assertSame(original, session.initialResult.await())
                session.completion.await()
                assertTrue(cancelled.isCompleted)
            }
        }

    @Test
    fun `updates start before collection while the initial result is available`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var executions = 0
            val first = update(true)
            val last = update()
            val session = ExecutionSession()
            session.start(DefaultCoroutineInterop) {
                executions++
                initial(
                    flow {
                        started.complete(Unit)
                        release.await()
                        emit(first)
                        emit(last)
                    },
                )
            }

            val result = session.initialResult.await() as IncrementalExecutionResult
            started.await()

            assertEquals(1, executions)
            assertFalse(session.completion.isDone)
            release.complete(Unit)
            session.completion.await()
            result.updates.toList().shouldContainExactly(first, last)
            assertThrows<IllegalStateException> { result.updates.toList() }
        }

    @Test
    fun `cancellation discards buffered updates before or after execution finishes`(): Unit =
        runExecutionTest<Unit> {
            for (finishExecution in listOf(false, true)) {
                val first = update(true)
                val readerPaused = CompletableDeferred<Unit>()
                val resumeReader = CompletableDeferred<Unit>()
                val buffered = CompletableDeferred<Unit>()
                val stopped = CompletableDeferred<Unit>()
                val session = ExecutionSession()
                session.start(DefaultCoroutineInterop) {
                    initial(
                        flow {
                            try {
                                emit(first)
                                readerPaused.await()
                                emit(update())
                                buffered.complete(Unit)
                                if (!finishExecution) awaitCancellation()
                            } finally {
                                stopped.complete(Unit)
                            }
                        },
                    )
                }
                val result = session.initialResult.await() as IncrementalExecutionResult
                val received = mutableListOf<Update>()
                val reader = async {
                    runCatching {
                        result.updates.collect {
                            received.add(it)
                            readerPaused.complete(Unit)
                            resumeReader.await()
                        }
                    }
                }

                try {
                    buffered.await()
                    if (finishExecution) session.completion.await()
                    result.cancel()
                    stopped.await()
                    resumeReader.complete(Unit)
                    val failure = reader.await().exceptionOrNull()
                    if (!finishExecution || failure != null) {
                        assertInstanceOf(CancellationException::class.java, failure)
                    }
                    received.shouldContainExactly(first)
                } finally {
                    result.cancel()
                    reader.cancelAndJoin()
                }
            }
        }

    @Test
    fun `cancel preserves its cause while discarding buffered updates and stopping execution`(): Unit =
        runExecutionTest<Unit> {
            val cause = CancellationException("Instrumentation cancelled execution")
            val buffered = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val session = ExecutionSession()
            session.start(DefaultCoroutineInterop) {
                initial(
                    flow {
                        try {
                            emit(update(true))
                            buffered.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    },
                )
            }
            val result = session.initialResult.await() as IncrementalExecutionResult
            buffered.await()

            session.cancel(cause)

            stopped.await()
            assertThrows<CancellationException> { session.completion.await() }
            val received = mutableListOf<Update>()
            val failure = assertThrows<CancellationException> { result.updates.toList(received) }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === cause })
            received.shouldBeEmpty()
        }

    @Test
    fun `cancelling an uncollected transformed result releases execution`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val session = ExecutionSession()
            session.start(DefaultCoroutineInterop) {
                initial(
                    flow {
                        started.complete(Unit)
                        awaitCancellation()
                    },
                ) { cancelled.complete(Unit) }
            }
            val result = session.initialResult.await() as IncrementalExecutionResult
            started.await()

            result.transform { it.extensions(emptyMap()) }.cancel()

            cancelled.await()
            assertThrows<CancellationException> { session.completion.await() }
            assertThrows<CancellationException> { result.updates.toList() }
        }

    @Test
    fun `reader failures cancel remaining work`(): Unit =
        runExecutionTest<Unit> {
            val cancelled = CompletableDeferred<Unit>()
            val session = ExecutionSession()
            session.start(DefaultCoroutineInterop) {
                initial(
                    flow {
                        emit(update(true))
                        awaitCancellation()
                    },
                ) { cancelled.complete(Unit) }
            }
            val result = session.initialResult.await() as IncrementalExecutionResult

            val thrown = assertThrows<IllegalStateException> {
                result.updates.collect { error("reader failed") }
            }

            assertEquals("reader failed", thrown.message)
            cancelled.await()
        }

    @Test
    fun `producer failures reach the caller before and after initial delivery`(): Unit =
        runExecutionTest<Unit> {
            val beforeInitial = IllegalStateException("initial failed")
            val failed = ExecutionSession()
            failed.start(DefaultCoroutineInterop) { throw beforeInitial }
            assertEquals(beforeInitial.message, assertThrows<IllegalStateException> { failed.initialResult.await() }.message)

            val afterInitial = IllegalStateException("deferred failed")
            val emitted = update(true)
            val session = ExecutionSession()
            session.start(DefaultCoroutineInterop) {
                initial(
                    flow {
                        emit(emitted)
                        throw afterInitial
                    }
                )
            }
            val result = session.initialResult.await() as IncrementalExecutionResult
            val received = mutableListOf<Update>()

            assertEquals(afterInitial.message, assertThrows<IllegalStateException> { result.updates.toList(received) }.message)
            assertEquals(afterInitial.message, assertThrows<IllegalStateException> { session.completion.await() }.message)
            received.shouldContainExactly(emitted)
        }

    @Test
    fun `cancelling the caller cancels an uncollected result`(): Unit =
        runExecutionTest<Unit> {
            val delivered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val caller = launch {
                withThreadLocalCoroutineContext {
                    val session = ExecutionSession()
                    session.start(DefaultCoroutineInterop) {
                        initial(flow { awaitCancellation() }) { cancelled.complete(Unit) }
                    }
                    session.initialResult.await()
                    delivered.complete(Unit)
                    awaitCancellation()
                }
            }
            delivered.await()

            caller.cancelAndJoin()

            assertTrue(cancelled.isCompleted)
        }

    @Test
    fun `cancelling the initial future cancels resolution`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val session = ExecutionSession()
            session.start(DefaultCoroutineInterop) {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            started.await()

            session.initialResult.cancel(true)

            cancelled.await()
            assertThrows<CancellationException> { session.completion.await() }
        }

    @Test
    fun `cancellation before the producer starts completes both futures`(): Unit =
        runExecutionTest<Unit> {
            val interop = object : CoroutineInterop by DefaultCoroutineInterop {
                override fun <T> scopedFuture(block: suspend CoroutineScope.() -> T): CompletableFuture<T> = CompletableFuture<T>().also { it.cancel(false) }
            }
            val session = ExecutionSession()
            session.start(interop) { error("Execution must not start") }

            assertThrows<CancellationException> { session.initialResult.await() }
            assertThrows<CancellationException> { session.completion.await() }
        }

    private fun initial(
        updates: Flow<Update> = emptyFlow(),
        cancel: () -> Unit = {},
    ) = IncrementalExecutionResultImpl(
        ExecutionResult.newExecutionResult().data(emptyMap<String, Any?>()).build(),
        listOf(PendingEntry("0", ResultPath.rootPath(), "later")),
        true,
        updates,
        cancel,
    )

    private fun update(hasNext: Boolean = false): Update =
        UpdateExecutionResultImpl(
            hasNext,
            emptyList(),
            if (hasNext) emptyList() else listOf(CompletedEntry("0", emptyList())),
            emptyList(),
        )
}
