@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import graphql.execution.ResultPath
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CompletedEntry
import viaduct.engine.api.IncrementalExecutionResult
import viaduct.engine.api.IncrementalExecutionResult.Update
import viaduct.engine.api.PendingEntry
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@Timeout(10)
class ExecutionSessionTest {
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
