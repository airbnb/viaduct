package viaduct.dataloader

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Helper class to store parts of the next tick functionality for further debugging if necessary
 */
class NextTickDebug(
    private val nextTickDispatcherName: String,
) {
    private val isFirstRunForTick = AtomicBoolean(true)
    private val tickStartNanoTime = AtomicReference<TickStart?>(null)
    private val numRunnables = AtomicLong(0)

    private class TickStart(val nanoTime: Long)

    fun onRunnableDispatch() {
        // null for tick start nanotime means it's the first runnable dispatched in the tick
        tickStartNanoTime.compareAndSet(null, TickStart(System.nanoTime()))
        numRunnables.incrementAndGet()
    }

    fun getNextTickMetadataAndReset(): NextTickMetadata {
        val tickDelayNs = tickStartNanoTime.getAndSet(null)?.let {
            System.nanoTime() - it.nanoTime
        } ?: -1
        return NextTickMetadata(
            dispatcherName = nextTickDispatcherName,
            numDispatchedRunnables = numRunnables.getAndSet(0),
            totalTickDelayNs = tickDelayNs
        )
    }
}
