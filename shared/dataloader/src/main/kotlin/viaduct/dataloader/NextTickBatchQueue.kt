package viaduct.dataloader

import java.lang.IllegalArgumentException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.launch

/**
 * A batching queue that leverages structured concurrency for thread safety.
 *
 * Items can be enqueued using enqueue(), and when flush() is called, the onFlush
 * callback will be called with all the previously enqueued items.
 *
 * This thread is non-blocking up to the nonBlockingQueueLimit provided. Beyond that,
 * enqueueing is a suspending operation.
 */
@DelicateCoroutinesApi
@ExperimentalCoroutinesApi
class NextTickBatchQueue<T>(
    nonBlockingQueueLimit: Int = Int.MAX_VALUE,
    private val batchQueueScope: CoroutineScope = GlobalScope,
    private val onFlush: (List<T>, NextTickDispatchingContext) -> Unit
) {
    private class NextTickBatch<T> {
        val items = mutableListOf<T>()
        var debugInfo: NextTickMetadata? = null
    }

    /**
     * Enqueues an item into the batching queue
     */
    suspend fun enqueue(item: T) {
        queueChannel.send(Item(item))
    }

    /**
     * Synchronous version of enqueue.
     */
    fun tryEnqueue(item: T) {
        queueChannel.trySend(Item(item))
    }

    /**
     * Indicates when a batch needs to be flushed
     */
    suspend fun flush(debugInfo: NextTickMetadata) {
        queueChannel.send(Flush(debugInfo))
    }

    /**
     * Synchronous version of a flush.
     */
    fun tryFlush(debugInfo: NextTickMetadata) {
        queueChannel.trySend(Flush(debugInfo))
    }

    companion object {
        interface QueueableItem

        data class Item<T>(
            val item: T
        ) : QueueableItem

        data class Flush(
            val debugInfo: NextTickMetadata?
        ) : QueueableItem
    }

    private val queueChannel = Channel<QueueableItem>(nonBlockingQueueLimit, BufferOverflow.SUSPEND)

    private val tickIndex = AtomicInteger(0)

    /**
     * Collects items coming into the queue channel and produces batches of items
     */
    @Suppress("UNCHECKED_CAST")
    private val itemBatchProducer: ReceiveChannel<NextTickBatch<T>>? by lazy {
        batchQueueScope.produce<NextTickBatch<T>> {
            while (true) {
                val nextBatch = NextTickBatch<T>()

                do {
                    val nextItem = queueChannel.receive()
                    if (nextItem is Flush) {
                        nextBatch.debugInfo = nextItem.debugInfo
                        break
                    }
                    nextItem as? Item<T> ?: throw IllegalArgumentException(
                        "Unexpected value in queue: $nextItem"
                    )
                    nextBatch.items.add(nextItem.item)
                } while (true)

                send(nextBatch)
            }
        }
    }

    init {
        batchQueueScope.launch {
            while (true) {
                val nextTickBatch = itemBatchProducer?.receive() ?: continue

                onFlush(
                    nextTickBatch.items,
                    NextTickDispatchingContext(
                        tickIndex.getAndIncrement(),
                        nextTickBatch.debugInfo
                    )
                )
            }
        }
    }
}
