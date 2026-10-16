package viaduct.engine.runtime.execution

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime.RequestScopeCancellationException
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest

@Timeout(10)
class RequestScopeTest {
    @Test
    fun `cancels child jobs after block completes`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()

            val result = RequestScope { factory ->
                factory(coroutineContext).launch {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
                started.await()
                "success"
            }

            assertEquals("success", result)
            assertTrue(stopped.isCompleted)
        }

    @Test
    fun `nested scopes reuse the supervisor and leave cleanup to the outer scope`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()

            RequestScope { outerFactory ->
                val supervisor = outerFactory(coroutineContext).coroutineContext[Job]
                RequestScope { innerFactory ->
                    val innerScope = innerFactory(coroutineContext)
                    assertSame(supervisor, innerScope.coroutineContext[Job])
                    innerScope.launch {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                    started.await()
                }

                assertTrue(supervisor!!.isActive)
                assertFalse(stopped.isCompleted)
            }

            assertTrue(stopped.isCompleted)
        }

    @Test
    fun `nested scopes keep the scopedFuture parent active`(): Unit =
        runExecutionTest<Unit> {
            val result = RequestScope {
                RequestScope {
                    scopedFuture { "ok" }.await()
                }
            }

            assertEquals("ok", result)
        }

    @Test
    fun `cancels the supervisor when the block fails`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()

            val failure = assertThrows<IllegalStateException> {
                supervisorScope {
                    RequestScope { factory ->
                        factory(coroutineContext).launch {
                            try {
                                started.complete(Unit)
                                awaitCancellation()
                            } finally {
                                stopped.complete(Unit)
                            }
                        }
                        started.await()
                        error("execution failed")
                    }
                }
            }

            assertEquals("execution failed", failure.message)
            assertTrue(stopped.isCompleted)
        }

    @Test
    fun `cancels child jobs when the block cancels itself`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()

            val failure = assertThrows<CancellationException> {
                RequestScope { factory ->
                    factory(coroutineContext).launch {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                    started.await()
                    throw CancellationException("self-cancel")
                }
            }

            assertEquals("self-cancel", failure.message)
            assertTrue(stopped.isCompleted)
        }

    @Test
    fun `cancellation propagates through multiple job levels`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()

            val result = RequestScope { factory ->
                factory(coroutineContext).launch {
                    launch {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
                started.await()
                "done"
            }

            assertEquals("done", result)
            assertTrue(stopped.isCompleted)
        }

    @Test
    fun `normal cleanup uses RequestScopeCancellationException`(): Unit =
        runExecutionTest<Unit> {
            val cancellationCause = CompletableDeferred<Throwable?>()

            RequestScope { factory ->
                factory(coroutineContext).launch { awaitCancellation() }
                    .invokeOnCompletion { cancellationCause.complete(it) }
            }

            assertInstanceOf(RequestScopeCancellationException::class.java, cancellationCause.await())
        }

    @Test
    fun `creates a new supervisor when the captured request scope has finished`(): Unit =
        runExecutionTest<Unit> {
            val (capturedContext, oldSupervisor) = RequestScope { factory ->
                currentCoroutineContext() to factory(coroutineContext).coroutineContext[Job]!!
            }
            assertFalse(oldSupervisor.isActive)

            val result = withContext(capturedContext.minusKey(Job)) {
                RequestScope { factory ->
                    val newSupervisor = factory(coroutineContext).coroutineContext[Job]!!
                    assertNotSame(oldSupervisor, newSupervisor)
                    assertTrue(newSupervisor.isActive)
                    scopedFuture { "ok" }.await()
                }
            }

            assertEquals("ok", result)
        }

    @Test
    fun `waits for child cleanup before returning`(): Unit =
        runExecutionTest<Unit> {
            val started = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val finishCleanup = CompletableDeferred<Unit>()
            val returned = CompletableDeferred<Unit>()
            val caller = async {
                val result = RequestScope { factory ->
                    factory(coroutineContext).launch {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                finishCleanup.await()
                            }
                        }
                    }
                    started.await()
                    "done"
                }
                returned.complete(Unit)
                result
            }

            try {
                cleanupStarted.await()
                assertFalse(returned.isCompleted)
                finishCleanup.complete(Unit)
                assertEquals("done", caller.await())
                assertTrue(returned.isCompleted)
            } finally {
                finishCleanup.complete(Unit)
                caller.cancelAndJoin()
            }
        }
}
