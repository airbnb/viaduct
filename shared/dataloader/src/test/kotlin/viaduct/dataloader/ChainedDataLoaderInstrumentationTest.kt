@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@ExperimentalCoroutinesApi
class ChainedDataLoaderInstrumentationTest {
    lateinit var subject: ChainedDataLoaderInstrumentation

    private class TestInstrumentation : DataLoaderInstrumentation {
        private class BatchState : DataLoaderInstrumentation.BatchState

        private val batchState = BatchState()

        override fun createBatchState(): DataLoaderInstrumentation.BatchState {
            return batchState
        }

        override fun <K> beginLoad(
            key: K,
            keyContext: Any?,
            batchState: DataLoaderInstrumentation.BatchState,
        ): DataLoaderInstrumentation.OnCompleteLoad {
            val testBatchState = this.batchState

            assertSame(testBatchState, batchState)
            return object : DataLoaderInstrumentation.OnCompleteLoad {
                override fun <V> onComplete(
                    result: V?,
                    exception: Throwable?,
                    batchState: DataLoaderInstrumentation.BatchState
                ) {
                    assertSame(testBatchState, batchState)
                }
            }
        }

        override fun <K> onAddBatchEntry(
            key: K,
            keyContext: Any?,
            batchState: DataLoaderInstrumentation.BatchState
        ) {
            assertEquals(this.batchState, batchState)
        }

        override suspend fun <K, V> instrumentBatchLoad(
            loadFn: GenericBatchLoadFn<K, V>,
            batchState: DataLoaderInstrumentation.BatchState
        ): GenericBatchLoadFn<K, V> {
            assertEquals(this.batchState, batchState)
            return loadFn
        }
    }

    @Test
    fun testSingleInstrumentationCorrectStates(): Unit =
        runBlocking {
            subject = ChainedDataLoaderInstrumentation(listOf(TestInstrumentation()))
            callInstrumentationSequence(subject)
        }

    @Test
    fun testMultipleInstrumentationCorrectStates(): Unit =
        runBlocking {
            subject = ChainedDataLoaderInstrumentation(listOf(TestInstrumentation(), TestInstrumentation()))
            callInstrumentationSequence(subject)
        }

    /**
     * Go through methods in the instrumentation to ensure that for each call, the ChainedDataLoaderInstrumentation will pass the correct instance of
     * state to each instrumentation. This is checked as the TestInstrumentation will do equality checks to ensure the passed in state is what is
     * expected.
     */
    private suspend fun callInstrumentationSequence(instrumentation: ChainedDataLoaderInstrumentation) {
        val batchState = instrumentation.createBatchState()
        val onComplete = instrumentation.beginLoad("something", null, batchState)
        instrumentation.onAddBatchEntry("something", null, batchState)
        instrumentation.instrumentBatchLoad<String, String>({ _, _ -> emptyList() }, batchState)
        onComplete.onComplete("result", null, batchState)
    }
}
