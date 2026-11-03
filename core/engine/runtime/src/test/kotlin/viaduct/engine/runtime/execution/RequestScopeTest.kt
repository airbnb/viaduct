package viaduct.engine.runtime.execution

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import viaduct.deferred.asDeferred
import viaduct.deferred.completableDeferred
import viaduct.deferred.thenApply
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

    @Test
    fun `cancelled nested caller waits for execution cleanup`(): Unit = verifyNestedCleanup(timesOut = false)

    @Test
    fun `timed out nested caller waits for execution cleanup`(): Unit = verifyNestedCleanup(timesOut = true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun verifyNestedCleanup(timesOut: Boolean) =
        runTest {
            withThreadLocalCoroutineContext {
                RequestScope { factory ->
                    val started = CompletableDeferred<Unit>()
                    val cleanupStarted = CompletableDeferred<Unit>()
                    val releaseCleanup = CompletableDeferred<Unit>()
                    val cleanupFinished = CompletableDeferred<Unit>()
                    val sibling = factory(coroutineContext).async {
                        cleanupFinished.await()
                        "sibling completed"
                    }
                    val caller = launch {
                        val body: suspend () -> Unit = {
                            RequestScope {
                                try {
                                    started.complete(Unit)
                                    awaitCancellation()
                                } finally {
                                    withContext(NonCancellable) {
                                        cleanupStarted.complete(Unit)
                                        releaseCleanup.await()
                                        cleanupFinished.complete(Unit)
                                    }
                                }
                            }
                        }
                        if (timesOut) withTimeoutOrNull(1000) { body() } else body()
                    }
                    try {
                        withTimeout(5000) { started.await() }
                        if (!timesOut) caller.cancel()
                        withTimeout(5000) { cleanupStarted.await() }
                        advanceUntilIdle()
                        assertFalse(caller.isCompleted, "Caller must wait for blocked cleanup")
                        assertTrue(factory(coroutineContext).coroutineContext[Job]!!.isActive)
                    } finally {
                        releaseCleanup.complete(Unit)
                        caller.join()
                    }
                    assertTrue(cleanupFinished.isCompleted)
                    assertEquals("sibling completed", withTimeout(5000) { sibling.await() })
                }
            }
        }

    @Test
    fun `already cancelled caller never starts nested execution`(): Unit =
        runExecutionTest {
            var started = false
            RequestScope {
                launch(Dispatchers.Unconfined) {
                    currentCoroutineContext()[Job]!!.cancel()
                    RequestScope { started = true }
                }.join()
            }
            assertFalse(started)
        }

    @Test
    fun `uncaught non-Exception throwable cancels request while ordinary failure stays isolated`(): Unit =
        runExecutionTest {
            for (wrapped in listOf(false, true)) {
                val fatal = object : Throwable("fatal background failure") {}
                val failure = if (wrapped) CompletionException(ExecutionException(fatal)) else fatal
                val cancellation = assertThrows<CancellationException> {
                    withTimeout(5000) {
                        RequestScope { factory ->
                            factory(coroutineContext).launch {
                                throw CompletionException(IllegalStateException("isolated failure", NoSuchMethodError("diagnostic cause")))
                            }.join()
                            assertTrue(factory(coroutineContext).coroutineContext[Job]!!.isActive)
                            factory(coroutineContext).launch { throw failure }.join()
                            awaitCancellation()
                        }
                    }
                }
                assertFalse(cancellation is TimeoutCancellationException)
                assertSame(fatal, generateSequence<Throwable>(cancellation) { it.cause }.last())
            }
        }

    @Test
    fun `fatal background completion handler cancels request and siblings`(): Unit =
        runExecutionTest {
            val fatal = NoSuchMethodError("fatal outside Java future completion")
            lateinit var sibling: CompletableFuture<String>
            val cancellation = assertThrows<CancellationException> {
                withTimeout(5000) {
                    RequestScope { factory ->
                        sibling = CompletableFuture()
                        sibling.asDeferred()
                        val source = completableDeferred<Int>()
                        val result = source.thenApply<Int, Int> { throw fatal }
                        factory(coroutineContext).launch { source.complete(1) }.join()
                        result.await()
                    }
                }
            }
            assertFalse(cancellation is TimeoutCancellationException)
            assertSame(fatal, generateSequence<Throwable>(cancellation) { it.cause }.last())
            assertTrue(sibling.isCancelled)
        }

    @Test
    fun `isolated scope fatal future leaves production request active`(): Unit =
        runExecutionTest {
            RequestScope { factory ->
                val requestJob = factory(coroutineContext).coroutineContext[Job]!!
                val failure = NoSuchMethodError("fatal shadow failure")
                val cancellation = assertThrows<CancellationException> {
                    RequestScope(isolated = true) { shadowFactory ->
                        assertNotSame(requestJob, shadowFactory(coroutineContext).coroutineContext[Job])
                        val future = CompletableFuture<String>()
                        val result = future.asDeferred()
                        future.completeExceptionally(failure)
                        result.await()
                    }
                }

                assertSame(failure, generateSequence<Throwable>(cancellation) { it.cause }.last())
                assertTrue(requestJob.isActive)
                assertEquals("production", scopedFuture { "production" }.await())
            }
        }

    @Test
    fun `fatal future cancels nested sibling request body and all shared scope work`(): Unit =
        runExecutionTest {
            val fatal = NoSuchMethodError("missing resolver method")
            val source = CompletableFuture<Int>()
            val siblingStarted = CompletableDeferred<Unit>()
            val siblingFinished = CompletableDeferred<Unit>()
            val fieldStarted = CompletableDeferred<Unit>()
            val fieldFinished = CompletableDeferred<Unit>()
            val nestedStarted = CompletableDeferred<Unit>()
            val nestedFinished = CompletableDeferred<Unit>()
            val completion = launch {
                siblingStarted.await()
                nestedStarted.await()
                fieldStarted.await()
                source.completeExceptionally(fatal)
            }

            val cancellation = assertThrows<CancellationException> {
                withTimeout(5000) {
                    RequestScope { factory ->
                        factory(coroutineContext).launch {
                            try {
                                fieldStarted.complete(Unit)
                                awaitCancellation()
                            } finally {
                                fieldFinished.complete(Unit)
                            }
                        }
                        launch {
                            RequestScope {
                                try {
                                    siblingStarted.complete(Unit)
                                    // This wait belongs to the execution body rather than a
                                    // request-parented Deferred or a field launched by factory.
                                    awaitCancellation()
                                } finally {
                                    siblingFinished.complete(Unit)
                                }
                            }
                        }
                        factory(coroutineContext).launch {
                            RequestScope {
                                try {
                                    val result = source.asDeferred()
                                    nestedStarted.complete(Unit)
                                    result.await()
                                } finally {
                                    nestedFinished.complete(Unit)
                                }
                            }
                        }
                        // The outer body must stop even though it never awaits the failing child.
                        awaitCancellation()
                    }
                }
            }

            assertFalse(cancellation is TimeoutCancellationException)
            assertSame(fatal, generateSequence<Throwable>(cancellation) { it.cause }.last())
            assertTrue(siblingFinished.isCompleted, "Sibling request body must finish")
            assertTrue(fieldFinished.isCompleted, "Shared field work must finish")
            assertTrue(nestedFinished.isCompleted, "Failing nested request must finish")
            completion.join()
        }
}
