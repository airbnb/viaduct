@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import io.mockk.spyk
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager

class BatchDispatchStrategyTest {
    private fun nextTickDispatcher() =
        NextTickDispatcher(
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            flagManager = FlagManager.Disabled
        )

    private lateinit var loadFn: GenericBatchLoadFn<String, String>
    private lateinit var loadCalls: MutableList<List<String>>
    private lateinit var instrumentation: DataLoaderInstrumentation
    private lateinit var onFailedDispatch: (keys: List<String>, throwable: Throwable) -> Unit
    private var failedDispatchKeys: List<String>? = null
    private var failedDispatchThrowable: Throwable? = null

    open inner class TestInst : DataLoaderInstrumentation

    @BeforeEach
    fun setup() {
        loadCalls = mutableListOf()
        loadFn = GenericBatchLoadFn { keys, _ ->
            loadCalls.add(keys)
            keys.map { Try(it) }
        }
        failedDispatchKeys = null
        failedDispatchThrowable = null
        onFailedDispatch = { keys, throwable ->
            failedDispatchKeys = keys
            failedDispatchThrowable = throwable
        }
        instrumentation = spyk(TestInst())
    }

    @Test
    fun `when batchScheduleFn throws, the triggering entry fails instead of hanging forever`(): Unit =
        runBlocking {
            // NextTickScheduleFn requires a NextTickDispatcher in the coroutine context. runBlocking's default
            // dispatcher isn't one, so calling it here throws, reproducing the real-world failure mode.
            val subject = BatchDispatchStrategy(loadFn, NextTickScheduleFn, DataLoaderOptions(), instrumentation)
            val result = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())

            subject.scheduleResult("key1", null, result, onFailedDispatch)

            val exception = runCatching { result.await() }.exceptionOrNull()
            assertTrue(exception is RuntimeException)
            assertTrue(exception?.message?.contains("NextTickDispatcher") == true)
            assertEquals(listOf("key1"), failedDispatchKeys)
            // kotlinx.coroutines' stacktrace recovery copies the exception when it crosses a suspension point,
            // so compare messages rather than instance identity.
            assertEquals(exception?.message, failedDispatchThrowable?.message)
            assertTrue(loadCalls.isEmpty())
        }

    @Test
    fun `after a schedule failure, the next caller starts a fresh batch instead of joining the dead one`(): Unit =
        runBlocking {
            val subject = BatchDispatchStrategy(loadFn, NextTickScheduleFn, DataLoaderOptions(), instrumentation)
            val result1 = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())

            subject.scheduleResult("key1", null, result1, onFailedDispatch)
            assertTrue(runCatching { result1.await() }.isFailure)

            // A caller with a valid NextTickDispatcher in context should not join the dead batch left behind
            // by the failed schedule attempt above; it should form its own new batch and succeed normally.
            withContext(nextTickDispatcher()) {
                val result2 = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
                subject.scheduleResult("key2", null, result2, onFailedDispatch)
                assertEquals("key2", result2.await())
            }
        }
}
