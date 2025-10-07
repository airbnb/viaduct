@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import io.mockk.coVerify
import io.mockk.spyk
import io.mockk.verify
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class ImmediateDispatchStrategyTest {
    private lateinit var subject: ImmediateDispatchStrategy<String, String>
    private lateinit var onFailedDispatch: (keys: List<String>, throwable: Throwable) -> Unit
    private var onFailedDispatchRan: Boolean = false

    private lateinit var loadFn: GenericBatchLoadFn<String, String>
    private lateinit var loadCalls: MutableList<List<String>>
    private lateinit var instrumentation: DataLoaderInstrumentation

    open inner class TestInst : DataLoaderInstrumentation

    @BeforeEach
    fun setup() {
        loadCalls = mutableListOf()
        loadFn = GenericBatchLoadFn { keys, _ ->
            loadCalls.add(keys)
            keys.map { Try(it) }
        }
        onFailedDispatchRan = false
        onFailedDispatch = { _, _ ->
            onFailedDispatchRan = true
        }

        instrumentation = spyk(TestInst())
        subject = ImmediateDispatchStrategy(loadFn, instrumentation)
    }

    @Test
    fun `load fn should get called for each load with the key arguments`(): Unit =
        runBlocking {
            val result1 = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            val result2 = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            async { subject.scheduleResult("key1", null, result1, onFailedDispatch) }
            async { subject.scheduleResult("key2", null, result2, onFailedDispatch) }

            assertEquals("key1", result1.await())
            assertEquals("key2", result2.await())

            assertEquals(loadCalls[0], listOf("key1"))
            assertEquals(loadCalls[1], listOf("key2"))
        }

    @Test
    fun `onFailedDispatch should run and result should be completed exceptionally if the loadFn throws`(): Unit =
        runBlocking {
            val throwingLoadFn = GenericBatchLoadFn<String, String> { _, _ ->
                throw RuntimeException("test")
            }
            subject = ImmediateDispatchStrategy(throwingLoadFn, instrumentation)
            val result = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            subject.scheduleResult("key1", null, result, onFailedDispatch)
            val exception = runCatching { result.await() }.exceptionOrNull()

            assertEquals("test", exception?.message)
            assertTrue(onFailedDispatchRan)
        }

    @Test
    fun `test instrumentation is called as expected for a single key`(): Unit =
        runBlocking {
            val result = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            subject.scheduleResult("key1", null, result, onFailedDispatch)
            assertEquals("key1", result.await())

            verify { instrumentation.createBatchState() }
            verify { instrumentation.onAddBatchEntry(eq("key1"), isNull(), any()) }
            coVerify { instrumentation.instrumentBatchLoad<String, String>(any(), any()) }
        }

    @Test
    fun `test instrumentation is called as expected for multiple keys`(): Unit =
        runBlocking {
            val result1 = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            val result2 = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            subject.scheduleResult("key1", null, result1, onFailedDispatch)
            subject.scheduleResult("key2", null, result2, onFailedDispatch)
            assertEquals("key1", result1.await())
            assertEquals("key2", result2.await())

            verify(exactly = 2) { instrumentation.createBatchState() }
            verify { instrumentation.onAddBatchEntry(eq("key1"), isNull(), any()) }
            verify { instrumentation.onAddBatchEntry(eq("key2"), isNull(), any()) }
            coVerify(exactly = 2) {
                instrumentation.instrumentBatchLoad<String, String>(any(), any())
            }
        }

    @Test
    fun `test result is completed with null if loadFn returns empty list`(): Unit =
        runBlocking {
            val result = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            loadFn = GenericBatchLoadFn { _, _ -> listOf() }

            subject = ImmediateDispatchStrategy(loadFn, instrumentation)
            subject.scheduleResult("someKey", null, result, onFailedDispatch)

            assertNull(result.await())
        }

    @Test
    fun `explicitly passed in keyContext should be used`(): Unit =
        runBlocking {
            val result = InternalDataLoader.Batch.BatchResult(CompletableDeferred<String?>())
            val keyContext = 1

            var batchKeyContexts: Map<String, Any?>? = null
            loadFn = GenericBatchLoadFn { keys, env ->
                batchKeyContexts = env.keyContexts
                keys.map { Try("someResult") }
            }
            subject = ImmediateDispatchStrategy(loadFn, instrumentation)
            subject.scheduleResult("someKey", keyContext, result, onFailedDispatch)

            assertEquals("someResult", result.await())
            assertEquals(mapOf("someKey" to 1), batchKeyContexts)
        }
}
