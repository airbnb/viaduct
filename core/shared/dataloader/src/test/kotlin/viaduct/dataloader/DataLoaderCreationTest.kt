@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import javax.inject.Provider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DataLoaderCreationTest {
    private object TestDispatchingContext : DispatchingContext

    private class TestLoader(private val immediate: Boolean, private val cacheErrors: Boolean = true) : DataLoader<String, String, String>() {
        val fetchedKeys = mutableListOf<Set<String>>()
        val keyContexts = mutableListOf<Map<String, Any?>>()
        var loaderCreations = 0
        var dispatchedBatches = 0
        var fail = false
        private val scheduled = mutableListOf<suspend (DispatchingContext) -> Unit>()

        override val batchScheduleFn: DispatchScheduleFn = { scheduled.add(it) }

        override fun shouldUseImmediateDispatch() = immediate

        override fun getDataLoaderOptions() = DataLoaderOptions(cachingExceptionsEnabled = cacheErrors)

        override val dataLoaderInstrumentationProvider = Provider<DataLoaderInstrumentation> {
            object : DataLoaderInstrumentation {
                override suspend fun <K, V> instrumentBatchLoad(
                    loadFn: GenericBatchLoadFn<K, V>,
                    batchState: DataLoaderInstrumentation.BatchState,
                ): GenericBatchLoadFn<K, V> {
                    dispatchedBatches++
                    return loadFn
                }
            }
        }

        override fun createInternalDataLoader(): InternalDataLoader<String, String, String> {
            loaderCreations++
            val delegate = super.createInternalDataLoader()
            // Lowercasing is just a test example to show that a subclass can change keys before cache lookup.
            return object : InternalDataLoader<String, String, String> by delegate {
                override suspend fun load(
                    key: String,
                    keyContext: Any?
                ) = delegate.load(key.lowercase(), keyContext)
            }
        }

        override suspend fun internalLoad(
            keys: Set<String>,
            environment: BatchLoaderEnvironment<String>
        ): Map<String, String?> {
            fetchedKeys.add(keys)
            keyContexts.add(environment.keyContexts)
            check(!fail) { "fetch failed" }
            return keys.filter { it != "missing" }.associateWith { it }
        }

        suspend fun load(
            key: String,
            context: Any? = null
        ) = internalDataLoader.load(key, context)

        fun clearAll() = internalDataLoader.clearAll()

        suspend fun dispatch() {
            val pending = scheduled.toList()
            scheduled.clear()
            pending.forEach { it(TestDispatchingContext) }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a subclass can customize keys before cache lookup`(immediate: Boolean) =
        runBlocking {
            val loader = TestLoader(immediate)
            assertEquals(0, loader.loaderCreations)
            val first = async(start = CoroutineStart.UNDISPATCHED) { loader.load("ONE", "caller") }
            val cached = async(start = CoroutineStart.UNDISPATCHED) { loader.load("one") }
            val second = async(start = CoroutineStart.UNDISPATCHED) { loader.load("two") }
            if (!immediate) assertEquals(0, loader.dispatchedBatches)
            loader.dispatch()

            assertEquals("one", first.await())
            assertEquals("one", cached.await())
            assertEquals("two", second.await())
            assertEquals(1, loader.loaderCreations)
            assertEquals(if (immediate) 2 else 1, loader.dispatchedBatches)
            assertEquals("caller", loader.keyContexts.first()["one"])
            assertEquals(setOf("one", "two"), loader.fetchedKeys.flatten().toSet())

            loader.clearAll()
            val missing = async(start = CoroutineStart.UNDISPATCHED) { loader.load("missing") }
            loader.dispatch()
            assertNull(missing.await())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `failed loads are cached only when exception caching is enabled`(cacheErrors: Boolean) =
        runBlocking {
            val loader = TestLoader(immediate = false, cacheErrors = cacheErrors)
            loader.fail = true
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { loader.load("one") } }
            loader.dispatch()
            assertEquals("fetch failed", first.await().exceptionOrNull()?.message)

            loader.fail = false
            val retry = async(start = CoroutineStart.UNDISPATCHED) { runCatching { loader.load("one") } }
            loader.dispatch()
            if (cacheErrors) {
                assertEquals("fetch failed", retry.await().exceptionOrNull()?.message)
                assertEquals(1, loader.fetchedKeys.size)
            } else {
                assertEquals("one", retry.await().getOrThrow())
                assertEquals(2, loader.fetchedKeys.size)
            }
        }
}
