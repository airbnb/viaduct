package viaduct.dataloader

import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextTickDebugTest {
    @Test
    fun `concurrent dispatches are counted and reset between ticks`() {
        val debug = NextTickDebug("dispatcher")
        val executor = Executors.newFixedThreadPool(4)

        try {
            val dispatches = (1..64).map { executor.submit { debug.onRunnableDispatch() } }
            dispatches.forEach { it.get() }
            val metadata = debug.getNextTickMetadataAndReset()

            assertEquals("dispatcher", metadata.dispatcherName)
            assertEquals(64L, metadata.numDispatchedRunnables)
            assertTrue(metadata.totalTickDelayNs >= 0L)

            val empty = debug.getNextTickMetadataAndReset()

            assertEquals(0L, empty.numDispatchedRunnables)
            assertEquals(-1L, empty.totalTickDelayNs)

            debug.onRunnableDispatch()
            val next = debug.getNextTickMetadataAndReset()

            assertEquals(1L, next.numDispatchedRunnables)
            assertTrue(next.totalTickDelayNs >= 0L)
        } finally {
            executor.shutdownNow()
        }
    }
}
