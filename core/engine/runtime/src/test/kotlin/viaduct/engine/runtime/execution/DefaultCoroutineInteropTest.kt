@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine.runtime.execution

import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultCoroutineInteropTest {
    @Test
    fun `returned future completes with the block's return value`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(executor) {
                "hello"
            }
            assertEquals("hello", future.join())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `block runs on a thread from the supplied executor`() {
        val executorThreadName = "default-coroutine-interop-test-thread"
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, executorThreadName) }
        try {
            val future = DefaultCoroutineInterop.enterThreadLocalCoroutineContext(executor) {
                Thread.currentThread().name
            }
            // kotlinx-coroutines may append a coroutine name suffix (e.g. " @coroutine#2") to the
            // thread name while the block runs, so match the executor's thread name as a prefix.
            future.join() shouldStartWith executorThreadName
        } finally {
            executor.shutdownNow()
        }
    }
}
