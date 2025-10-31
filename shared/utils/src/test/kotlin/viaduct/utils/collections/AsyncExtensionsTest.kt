@file:Suppress("Detekt.ForbiddenImport")

package viaduct.utils.collections

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class AsyncExtensionsTest {
    @Test
    fun testParallelMap() {
        runBlocking {
            val testList = listOf(1, 2, 3, 4)
            val completionBarrier = CompletableDeferred<Boolean>()
            val inspectionChannel = Channel<Int>()
            val results =
                async {
                    testList.parallelMap {
                        inspectionChannel.send(it)
                        completionBarrier.await()
                        it
                    }.toList()
                }

            withTimeout(100) {
                val resultSet = mutableSetOf<Int>()
                for (i in 1..4) {
                    resultSet.add(inspectionChannel.receive())
                }
                assert(resultSet.size == 4)
                completionBarrier.complete(true)
            }
            results.await() shouldBe testList
        }
    }

    @Test
    fun testParallelMap_shouldPropagateExceptions() {
        runBlocking {
            val exception = shouldThrow<IllegalArgumentException> {
                0.until(4).parallelMap(2, 2) {
                    if (it == 3) {
                        throw IllegalArgumentException("Test!")
                    }
                }.toList()
            }
            exception.message shouldBe "Test!"
        }
    }

    @Test
    fun testParallelMap_shouldLimitReadAhead() {
        runBlocking<Unit> {
            val firstItemCompletionBarrier = CompletableDeferred<Boolean>()
            val secondItemCompletionBarrier = CompletableDeferred<Boolean>()

            val itemsProcessed = AtomicInteger(0)

            // Use dispatchers.default so we actually have parallel execution
            val job =
                launch(Dispatchers.Default) {
                    var nextItem = 0
                    0.until(100).parallelMap(2, 20) {
                        if (it == 0) {
                            firstItemCompletionBarrier.await()
                        }

                        if (it == 60) {
                            secondItemCompletionBarrier.await()
                        }

                        if (firstItemCompletionBarrier.isActive) {
                            it shouldBeLessThan 20
                        }

                        if (secondItemCompletionBarrier.isActive) {
                            it shouldBeLessThan 80
                        }

                        itemsProcessed.incrementAndGet()
                        it
                    }.collect {
                        it shouldBe nextItem
                        nextItem++
                    }
                }

            withTimeout(2000) {
                while (itemsProcessed.get() < 19) {
                    yield()
                }

                // Wait some time to confirm that no further items are processed
                delay(10)
                firstItemCompletionBarrier.complete(true)

                while (itemsProcessed.get() < 79) {
                    yield()
                }

                // Wait some time to confirm that no further items are processed
                delay(10)
                secondItemCompletionBarrier.complete(true)

                job.join()

                itemsProcessed.get() shouldBe 100
            }
        }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    @Test
    fun testParallelMapBuffered() {
        runBlocking {
            val testList = listOf(1, 2, 3, 4)
            val inspectionChannel1 = Channel<Int>()
            val inspectionChannel2 = Channel<Int>()
            var currentInspectionChannel = inspectionChannel1

            val semaphoreChannel = BroadcastChannel<Boolean>(1)
            val results =
                async {
                    testList.parallelMap(2, 2) {
                        // start listening for termination signal
                        val semaphore = semaphoreChannel.openSubscription()

                        // send my value in for inspection
                        currentInspectionChannel.send(it)

                        // wait for the go-ahead to move on
                        semaphore.receive()

                        it
                    }.toList()
                }

            withTimeout(500) {
                launch {
                    val val1 = inspectionChannel1.receive()
                    val val2 = inspectionChannel1.receive()

                    // we should have received 1 and 2
                    assert(inspectionChannel1.isEmpty)
                    setOf(val1, val2) shouldBe setOf(1, 2)
                    // swap the inspection channel

                    currentInspectionChannel = inspectionChannel2

                    // raise the semaphore so the first set of things can move on
                    semaphoreChannel.send(true)
                }
            }

            withTimeout(500) {
                launch {
                    val val1 = inspectionChannel2.receive()
                    val val2 = inspectionChannel2.receive()

                    // we should have received 3 and 4
                    assert(inspectionChannel2.isEmpty)
                    setOf(val1, val2) shouldBe setOf(3, 4)

                    // raise the semaphore so the second set of things can move on
                    semaphoreChannel.send(true)
                }
            }

            results.await() shouldBe testList
        }
    }
}
