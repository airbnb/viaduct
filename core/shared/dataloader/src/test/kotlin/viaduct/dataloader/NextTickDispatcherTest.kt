@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.service.api.spi.FlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class NextTickDispatcherTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `request dispatcher preserves worker instrumentation and counts each flush once`(metricsEnabled: Boolean) =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            var instrumentedRuns = 0
            val instrumentedWorker = object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable
                ) {
                    worker.dispatch(context) {
                        instrumentedRuns++
                        block.run()
                    }
                }
            }
            val sourceOwner = Job()
            val requestOwner = Job()
            try {
                val source = NextTickDispatcher(instrumentedWorker, UnconfinedTestDispatcher(testScheduler), metricsEnabled = metricsEnabled, parentJob = sourceOwner)
                val request = source.forRequest(requestOwner, CoroutineExceptionHandler { _, cause -> throw cause })

                CoroutineScope(request + requestOwner).launch {}
                advanceUntilIdle()

                assertEquals(1, instrumentedRuns)
                assertEquals(if (metricsEnabled) 1L else 0L, source.flushCount)
                assertEquals(source.flushCount, request.flushCount)
            } finally {
                requestOwner.cancel()
                sourceOwner.cancel()
            }
        }

    @Test
    fun `cancelling one request stops its callback while another request keeps working`() =
        runTest {
            val sourceOwner = Job()
            val firstOwner = Job()
            val secondOwner = Job()
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            val secondResult = CompletableDeferred<Int>()
            val unexpectedFailure = CoroutineExceptionHandler { _, cause -> throw cause }
            try {
                val source = NextTickDispatcher(StandardTestDispatcher(testScheduler), UnconfinedTestDispatcher(testScheduler), parentJob = sourceOwner)
                val first = source.forRequest(firstOwner, unexpectedFailure)
                val second = source.forRequest(secondOwner, unexpectedFailure)
                CoroutineScope(first + firstOwner).launch {
                    nextTick(coroutineContext) {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            stopped.complete(Unit)
                        }
                    }
                }
                CoroutineScope(second + secondOwner).launch {
                    nextTick(coroutineContext) {
                        releaseSecond.await()
                        secondResult.complete(42)
                    }
                }
                runCurrent()
                assertTrue(started.isCompleted)

                firstOwner.cancel()
                runCurrent()
                assertTrue(stopped.isCompleted)

                releaseSecond.complete(Unit)
                advanceUntilIdle()
                assertEquals(42, secondResult.await())
                assertTrue(sourceOwner.isActive)
                assertTrue(secondOwner.isActive)
            } finally {
                firstOwner.cancel()
                secondOwner.cancel()
                sourceOwner.cancel()
            }
        }

    @Test
    fun `a queue worker that cannot start reports failure to the supplied handler without a parent job`() =
        runBlocking {
            val failure = RejectedExecutionException("queue executor is unavailable")
            val reported = CompletableDeferred<Throwable>()
            val rejectingQueue = object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable
                ) {
                    throw failure
                }
            }
            assertSame(
                failure,
                assertThrows(RejectedExecutionException::class.java) {
                    NextTickDispatcher(
                        nextTickQueueDispatcher = rejectingQueue,
                        nextTickExceptionHandler = CoroutineExceptionHandler { _, cause -> reported.complete(cause) },
                        parentJob = null,
                    )
                }
            )

            assertSame(failure, withTimeout(5_000) { reported.await() })
        }

    @Test
    fun `undispatched start requires the exact running dispatcher`(): Unit =
        runTest {
            val worker = StandardTestDispatcher(testScheduler)
            val queue = UnconfinedTestDispatcher(testScheduler)
            val dispatcher = NextTickDispatcher(worker, queue)
            val other = NextTickDispatcher(worker, queue)

            assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(EmptyCoroutineContext))
            assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(dispatcher))
            withContext(dispatcher) {
                assertTrue(dispatcher.isRunningOnCurrentThread())
                assertFalse(other.isRunningOnCurrentThread())
                assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(other))
                assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(worker))
                val result = async(start = undispatchedStartIfRunning(coroutineContext)) { 42 }
                assertTrue(result.isCompleted)
                assertEquals(42, result.await())
                yield()
                assertEquals(CoroutineStart.UNDISPATCHED, undispatchedStartIfRunning(coroutineContext))
            }
            assertFalse(dispatcher.isRunningOnCurrentThread())
            assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(dispatcher))
        }

    @Test
    fun `nested dispatchers restore ownership after throwing runnables`(): Unit =
        runTest {
            val worker = object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable
                ) = block.run()
            }
            val queue = StandardTestDispatcher(testScheduler)
            val outer = NextTickDispatcher(worker, queue)
            val inner = NextTickDispatcher(worker, queue)
            val failure = IllegalStateException("runnable failed")

            assertSame(
                failure,
                assertThrows(IllegalStateException::class.java) {
                    outer.dispatch(
                        EmptyCoroutineContext,
                        Runnable {
                            assertSame(
                                failure,
                                assertThrows(IllegalStateException::class.java) {
                                    inner.dispatch(
                                        EmptyCoroutineContext,
                                        Runnable {
                                            assertEquals(CoroutineStart.UNDISPATCHED, undispatchedStartIfRunning(inner))
                                            assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(outer))
                                            throw failure
                                        }
                                    )
                                }
                            )
                            assertEquals(CoroutineStart.UNDISPATCHED, undispatchedStartIfRunning(outer))
                            assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(inner))
                            throw failure
                        }
                    )
                }
            )
            assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(outer))
            assertEquals(CoroutineStart.DEFAULT, undispatchedStartIfRunning(inner))
        }

    @Test
    fun `inline child finishes before the next tick flushes`(): Unit =
        runTest {
            val dispatcher = NextTickDispatcher(StandardTestDispatcher(testScheduler), UnconfinedTestDispatcher(testScheduler))
            val order = mutableListOf<String>()

            withContext(dispatcher) {
                nextTick(coroutineContext) { order.add("tick") }
                val child = async(start = undispatchedStartIfRunning(coroutineContext)) { order.add("child") }
                assertTrue(child.isCompleted)
                child.await()
                order.add("parent")
                assertEquals(listOf("child", "parent"), order)
            }
            advanceUntilIdle()

            assertEquals(listOf("child", "parent", "tick"), order)
        }

    @Test
    fun `parent cancellation stops callbacks and queue workers`() =
        runBlocking {
            withTimeout(5_000) {
                val parent = Job()
                val queueJobs = ConcurrentLinkedQueue<Job>()
                val queueDispatcher = object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable
                    ) {
                        queueJobs.add(checkNotNull(context[Job]))
                        Dispatchers.IO.dispatch(context, block)
                    }
                }
                val dispatcher = NextTickDispatcher(nextTickQueueDispatcher = queueDispatcher, parentJob = parent)
                val callbackStarted = CompletableDeferred<Unit>()
                val callbackStopped = CompletableDeferred<Unit>()
                try {
                    withContext(dispatcher) {
                        nextTick(coroutineContext) {
                            callbackStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                callbackStopped.complete(Unit)
                            }
                        }
                    }
                    callbackStarted.await()

                    parent.cancelAndJoin()

                    callbackStopped.await()
                    assertTrue(queueJobs.isNotEmpty())
                    queueJobs.toSet().forEach { worker ->
                        worker.join()
                        assertTrue(worker.isCancelled)
                    }
                } finally {
                    parent.cancel()
                    dispatcher.nextTickSupervisorJob.cancel()
                    queueJobs.forEach { it.cancel() }
                }
            }
        }

    private fun nextTickDispatcher() =
        NextTickDispatcher(
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            flagManager = FlagManager.Disabled,
        )

    @Test
    fun testSimpleCase() {
        val nextTickDispatcher = nextTickDispatcher()
        val queue = ConcurrentLinkedQueue<Int>()
        val countdown = CountDownLatch(3)

        runBlockingWithTimeout(nextTickDispatcher) {
            nextTick(coroutineContext) {
                queue.add(2)
                countdown.countDown()
                // **READERS NOTE:**
                // Complete supervisorjob for the dispatchers nextTickQueue. We're doing this so that we ensure we
                // can prevent the test from completing _before_ nextTick is called (given that nextTick isn't launched
                // within the `runBlocking` Job). We call `.join` on the nextTickSupervisorJob below, which will suspend
                // until `.complete` is called. If for some reason our tests are failing, due to a bad change in
                // `NextTickDispatcher`, and we for some reason never run this code because `nextTick` is broken, we
                // guard against the test failing by running all of these tests wrapped in a `runBlockingWithTimeout`.
                nextTickDispatcher.nextTickSupervisorJob.complete()
            }

            launch {
                queue.add(1)
                countdown.countDown()
            }

            launch {
                queue.add(1)
                countdown.countDown()
            }

            nextTickDispatcher.nextTickSupervisorJob.join()
        }

        assertEquals(listOf(1, 1, 2), queue.toList())
    }

    @Test
    fun testNextTickThrowsException() {
        val queue = ConcurrentLinkedQueue<Int>()
        val countdown = CountDownLatch(4)
        val nextTickDispatcher =
            NextTickDispatcher(
                nextTickExceptionHandler = CoroutineExceptionHandler { _, _ -> countdown.countDown() },
            )

        runBlockingWithTimeout(nextTickDispatcher) {
            supervisorScope {
                nextTick(coroutineContext) {
                    throw RuntimeException("Sample Exception")
                    // countdown.countDown() here is handled by the exception handler
                }
                nextTick(coroutineContext) {
                    queue.add(2)
                    countdown.countDown()
                    // complete supervisorjob for the dispatchers nextTickQueue
                    nextTickDispatcher.nextTickSupervisorJob.complete()
                }

                launch {
                    queue.add(1)
                    countdown.countDown()
                }

                launch {
                    queue.add(1)
                    countdown.countDown()
                }

                nextTickDispatcher.nextTickSupervisorJob.join()
            }
        }

        assertEquals(listOf(1, 1, 2), queue.toList())
    }

    @Test
    fun testRunnableThrowsException() {
        val queue = ConcurrentLinkedQueue<Int>()
        val countdown = CountDownLatch(3)
        val nextTickDispatcher = nextTickDispatcher()
        val exceptionHandler = CoroutineExceptionHandler { _, _ -> countdown.countDown() }

        runBlockingWithTimeout(nextTickDispatcher + exceptionHandler) {
            supervisorScope {
                nextTick(coroutineContext) {
                    queue.add(2)
                    countdown.countDown()
                    nextTickDispatcher.nextTickSupervisorJob.complete()
                }
                launch {
                    queue.add(1)
                    countdown.countDown()
                }
                launch {
                    throw RuntimeException("Sample Exception")
                    // countdown.countDown() here is handled by the exception handler
                }
                nextTickDispatcher.nextTickSupervisorJob.join()
            }
        }

        assertEquals(listOf(1, 2), queue.toList())
    }

    /**
     * This test ensures you can register nextTick in a different coroutine and still have the expected behavior.
     * This is a regression test -- previously, we weren't properly creating a new Job() for the nextTick block, which
     * would could result in the contents of the nextTick block to never be executed. This test allows us to emulate
     * that behavior.
     */
    @Test
    fun testNickTickFromNewCoroutine() {
        val queue = ConcurrentLinkedQueue<Int>()
        val dispatcher = nextTickDispatcher()
        val countdown = CountDownLatch(3)
        runBlockingWithTimeout(dispatcher) {
            supervisorScope {
                val one = CompletableDeferred<Unit>()
                val two = CompletableDeferred<Unit>()

                launch {
                    nextTick(coroutineContext) {
                        one.complete(Unit)
                        two.complete(Unit)
                        queue.add(2)
                        countdown.countDown()
                        dispatcher.nextTickSupervisorJob.complete()
                    }
                }

                launch {
                    queue.add(1)
                    one.await()
                    countdown.countDown()
                }

                launch {
                    queue.add(1)
                    two.await()
                    countdown.countDown()
                }

                dispatcher.nextTickSupervisorJob.join()
            }
        }

        assertEquals(listOf(1, 1, 2), queue.toList())
    }
}

private fun runBlockingWithTimeout(
    context: CoroutineContext,
    timeout: Long = 1000L,
    block: suspend CoroutineScope.() -> Unit
) = runBlocking { withTimeout(timeout) { withContext(context) { block() } } }
