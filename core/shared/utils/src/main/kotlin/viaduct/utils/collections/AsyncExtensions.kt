package viaduct.utils.collections

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Runs the block over up to _parallelWorkers_ elements in parallel.
 *
 * e.g.
 * listOf(1, 2, 3, 4).parallelMap(2) {
 *     delay(1000)
 *     println(it)
 * }
 *
 * This will run in 2 seconds total. After a second we'll see 1, 2. After another second we'll see 3, 4
 * @param parallelWorkers the maximum number of operations to run in parallel
 * @param maxReadAhead the maximum number of results to buffer in memory while waiting for earlier
 *                     operations to complete
 * @param block the operation to execute on each value of the input
 */
@ExperimentalCoroutinesApi
fun <T, R> Iterable<T>.parallelMap(
    parallelWorkers: Int = 10,
    maxReadAhead: Int = parallelWorkers,
    block: suspend CoroutineScope.(T) -> R
) = channelFlow {
    require(maxReadAhead >= parallelWorkers) {
        "maxReadAhead: $maxReadAhead should be greater than or equal to $parallelWorkers"
    }
    val readAheadSemaphore = Semaphore(maxReadAhead)
    // Producer that limits the number of pending results
    val inputProducer =
        produce {
            this@parallelMap.forEachIndexed { index, it ->
                readAheadSemaphore.acquire()
                send(Pair(index, it))
            }
        }
    val processorChannel = Channel<Pair<Int, R>>()
    // Setup the workers
    // Consume all the values in parallel
    launch {
        parallelConsume(parallelWorkers, inputProducer) {
            processorChannel.send(Pair(it.first, block(it.second)))
        }
        processorChannel.close()
    }
    // Buffered Ordered Output
    var nextIndex = 0
    // Initialize the result buffer based on the number of parallel workers as a best estimate
    val buffer = ArrayList<Pair<Int, R>?>(parallelWorkers)

    // Sends the next result to the output and releases a new permit on the read ahead semaphore
    suspend fun sendNextResult(result: R) {
        send(result)
        readAheadSemaphore.release()
        nextIndex++
    }

    // Drains the read ahead buffer of all available ordered results
    suspend fun drainBuffer() {
        do {
            val nextBufferIndex = nextIndex % maxReadAhead
            val bufferedResult = buffer.getOrNull(nextBufferIndex)
            if (bufferedResult != null) {
                check(bufferedResult.first == nextIndex) {
                    "Expected buffered result with index $nextIndex, found ${bufferedResult.first}"
                }

                buffer[nextBufferIndex] = null
                sendNextResult(bufferedResult.second)
            }
        } while (bufferedResult != null)
    }
    // Send the buffered results to the output channel while the processorChannel is open
    while (!processorChannel.isClosedForReceive) {
        drainBuffer()
        // Next try to read the next result off the channel and either send it if it is the next one
        // or add it to the buffer.
        val nextResult = processorChannel.receiveCatching().getOrNull() ?: continue
        if (nextResult.first == nextIndex) {
            sendNextResult(nextResult.second)
        } else {
            // Add the next result into the buffer if it is not ready to be returned
            val nextResultIndex = nextResult.first % maxReadAhead

            buffer.expandWith(nextResultIndex + 1, null)
            check(buffer[nextResultIndex] == null) {
                "Expected empty buffer slot for ${nextResult.first} but found ${buffer[nextResultIndex]?.first}"
            }

            buffer[nextResultIndex] = nextResult
        }
    }

    drainBuffer()
}

/**
 * Consume items from the input channel in parallel controlling the number of parallel workers
 *
 * @param T the type of the item to be consumed
 * @param parallelWorkers the number of parallel workers
 * @param input the input channel
 * @param block callback to process an item
 */
@ExperimentalCoroutinesApi
internal suspend fun <T : Any> parallelConsume(
    parallelWorkers: Int,
    input: ReceiveChannel<T>,
    block: suspend (T) -> Unit
) {
    coroutineScope {
        // Launch up to parallelWorkers workers to process items from the channel
        repeat(parallelWorkers) {
            // Short circuit launching a new coroutine if there is no item to consume
            val firstValue = input.receiveCatching().getOrNull() ?: return@repeat
            launch {
                block(firstValue)
                for (value in input) {
                    block(value)
                }
            }
        }
    }
}
