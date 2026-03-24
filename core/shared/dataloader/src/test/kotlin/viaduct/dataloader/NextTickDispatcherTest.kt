@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager

class NextTickDispatcherTest {
    private fun nextTickDispatcher() =
        NextTickDispatcher(
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            flagManager = FlagManager.disabled,
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
