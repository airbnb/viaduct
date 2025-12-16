@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import viaduct.dataloader.InternalDataLoader.Companion.genericBatchLoadFn
import viaduct.service.api.spi.FlagManager
import viaduct.utils.collections.parallelMap

/**
 * Uses `runBlocking` instead of `runBlockingTest` or `runTest` since we have seen strange behavior with runBlockingTest when trying to test the real
 * concurrency on this class. If the kotlin.coroutines library is updated to 1.6+ we can try to use runTest again since the kotlin team rewrote the
 * test lib with that version.
 *
 * We use the tests here as a sort of full end-to-end test for the InternalDataLoader + InternalDispatchStrategy test, so instead of mocking out the
 * InternalDispatchStrategy, we instead initialize real instances of InternalDispatchStrategy and compare results of the load logic against the
 * actual results.
 *
 */
// @CoroutinesTimeout(5000, true) // JaCoCo bytecode instrumentation conflicts with coroutines debug instrumentation
// See: https://github.com/jacoco/jacoco/issues/1357
class InternalDataLoaderTest {
    enum class TestDispatchStrategy {
        BATCH,
        IMMEDIATE
    }

    private val testDispatchStrategies = TestDispatchStrategy.values().toList()

    /**
     * Used for testing real concurrency.
     */
    private fun realNextTickDispatcher() =
        NextTickDispatcher(
            Dispatchers.Default,
            Executors.newCachedThreadPool().asCoroutineDispatcher(),
            flagManager = FlagManager.disabled
        )

    /**
     * For all the rest of our business logic tests.
     */
    private fun singleThreadedNextTickDispatcher() =
        NextTickDispatcher(
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            flagManager = FlagManager.disabled,
        )

    @ParameterizedTest
    @EnumSource(TestDispatchStrategy::class)
    fun testSimpleDataLoader(testDispatchStrategy: TestDispatchStrategy) {
        runBlocking(singleThreadedNextTickDispatcher()) {
            val identityLoader =
                createLoader<Int, Int>(testDispatchStrategy) { keys ->
                    keys.associateWith { it }
                }

            val resultDeferred = async { identityLoader.load(1) }
            val result = resultDeferred.await()

            assertEquals(1, result)
        }
    }

    @ParameterizedTest
    @EnumSource(TestDispatchStrategy::class)
    fun testLoadMany(testDispatchStrategy: TestDispatchStrategy) {
        runBlocking(singleThreadedNextTickDispatcher()) {
            val (identityLoader, _) = trackableLoader<Int>(testDispatchStrategy)

            val result1Deferred = async { identityLoader.loadMany(listOf(1, 2)) }
            val result2Deferred = async { identityLoader.loadMany(listOf(3, 4)) }
            val result1 = result1Deferred.await()
            val result2 = result2Deferred.await()

            assertEquals(listOf(1, 2), result1)
            assertEquals(listOf(3, 4), result2)

            val emptyResultDeferred = async { identityLoader.loadMany(listOf()) }
            val emptyResult = emptyResultDeferred.await()

            assertEquals(emptyList<Any>(), emptyResult)
        }
    }

    /**
     * Tests that are specific to the batching dispatch strategy and assume some batching behavior.
     */
    @Nested
    inner class BatchingDispatchTests {
        @Test
        fun testSimpleBatching() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, loadCalls) = trackableLoader<Int>(TestDispatchStrategy.BATCH)
                val result1Def = async { loader.load(1) }
                val result2Def = async { loader.load(2) }

                val results = listOf(result1Def, result2Def).awaitAll()

                assertEquals(listOf(1, 2), results)
                assertEquals(listOf(1, 2), loadCalls[0])
            }
        }

        @Test
        fun testBatchSize() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, loadCalls) = trackableLoader<Int>(TestDispatchStrategy.BATCH, 2)
                val result1Def = async { loader.load(1) }
                val result2Def = async { loader.load(2) }
                val result3Def = async { loader.load(3) }
                val result4Def = async { loader.load(4) }

                val results = listOf(result1Def, result2Def, result3Def, result4Def).awaitAll()

                assertEquals(listOf(1, 2, 3, 4), results)
                assertEquals(listOf(1, 2), loadCalls[0])
                assertEquals(listOf(3, 4), loadCalls[1])
            }
        }

        @RepeatedTest(100)
        fun testMaxBatchSizeThreadSafety() {
            runBlocking(realNextTickDispatcher()) {
                val maxBatchSize = Random.nextInt(2, 4)
                val itemCount = Random.nextInt(5, 10)
                val concurrentLoaders = Random.nextInt(5, 10)
                measureTimeMillis {
                    val startLatch = CountDownLatch(1)
                    val loadLatch = CountDownLatch(1)
                    val (loader, loadCalls) = trackableLoader<Int>(TestDispatchStrategy.BATCH, maxBatchSize)

                    val loadCallIndex = AtomicInteger(0)
                    val deferredResults =
                        (1..concurrentLoaders).mapIndexed { _, _ ->
                            async {
                                startLatch.await() // Wait for the signal to start
                                (1..itemCount)
                                    .map { _ ->
                                        async {
                                            loadLatch.await()
                                            loader.load(loadCallIndex.incrementAndGet())
                                        }
                                    }.awaitAll()
                            }
                        }

                    startLatch.countDown() // Signal all coroutines to start
                    loadLatch.countDown() // Signal all coroutines to load

                    val allResults = deferredResults.awaitAll().flatten()

                    assertEquals(itemCount * concurrentLoaders, allResults.size)

                    val expectedTotalLoadCalls = loadCallIndex.get()
                    var actualTotalLoadCalls = 0
                    loadCalls.forEach { call ->
                        assertTrue(call.size <= maxBatchSize)
                        actualTotalLoadCalls += call.size
                    }
                    assertEquals(expectedTotalLoadCalls, actualTotalLoadCalls)
                }.also {
                    println("Completed in $it ms $maxBatchSize:$itemCount:$concurrentLoaders")
                }
            }
        }

        @Test
        fun testBatchingWithLongRunningTask() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, loadCalls) = trackableLoader<String>(TestDispatchStrategy.BATCH)
                listOf(
                    launch {
                        loader.load("A")
                    },
                    // this is going to do a small amount of CPU intensive work, and then do a .load
                    // this should not affect batching
                    launch {
                        var sum = 0
                        for (i in 0..100000) {
                            sum += i
                        }
                        loader.load("B")
                    }
                ).joinAll()

                assertEquals(listOf("A", "B"), loadCalls[0])
            }
        }

        @Test
        fun testCachingOfRepeatedRequests() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, loadCalls) = trackableLoader<String>(TestDispatchStrategy.BATCH)
                val (a, b) =
                    listOf(
                        async { loader.load("A") },
                        async { loader.load("B") }
                    ).awaitAll()

                assertEquals("A", a)
                assertEquals("B", b)
                assertEquals(listOf("A", "B"), loadCalls[0])

                val (a2, c) =
                    listOf(
                        async { loader.load("A") },
                        async { loader.load("C") }
                    ).awaitAll()

                assertEquals("A", a2)
                assertEquals("C", c)
                assertEquals(listOf("A", "B"), loadCalls[0])
                assertEquals(listOf("C"), loadCalls[1])
            }
        }

        @Test
        fun testCachingWithCacheKeyOverrides() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val loadCalls = mutableListOf<List<Map<String, String>>>()
                val dispatchStrategy = createDispatchStrategy(
                    TestDispatchStrategy.BATCH,
                    object : MappedBatchLoadFn<Map<String, String>, String> {
                        override suspend fun load(
                            keys: Set<Map<String, String>>,
                            env: BatchLoaderEnvironment<Map<String, String>>
                        ): Map<Map<String, String>, String?> {
                            loadCalls.add(keys.toList())
                            return keys.associateWith { it["value"] }
                        }
                    },
                    DataLoaderOptions(1000),
                    object : DataLoaderInstrumentation {},
                )
                val loader =
                    InternalDataLoader.newLoader(
                        dispatchStrategy,
                        cacheKeyFn = { key -> "${key["id"]}:${key["fields"]}" },
                        cacheKeyMatchFn = { newKey, existingKey ->
                            val newParts = newKey.split(":")
                            val oldParts = existingKey.split(":")
                            newParts[0] == oldParts[0] && oldParts[1].contains(newParts[1])
                        }
                    )

                val key1 = mapOf("id" to "1", "fields" to "x,y", "value" to "A")
                val key2 = mapOf("id" to "2", "fields" to "x,y", "value" to "B")
                // Should hit cache for key1
                val key3 = mapOf("id" to "1", "fields" to "y", "value" to "C")
                // Should not hit cache for key1
                val key4 = mapOf("id" to "1", "fields" to "y,z", "value" to "D")

                val (a, b) =
                    listOf(
                        async { loader.load(key1) },
                        async { loader.load(key2) }
                    ).awaitAll()

                assertEquals("A", a)
                assertEquals("B", b)
                assertEquals(listOf(key1, key2), loadCalls[0])

                val (a2, d) =
                    listOf(
                        async { loader.load(key3) },
                        async { loader.load(key4) }
                    ).awaitAll()

                assertEquals("A", a2)
                assertEquals("D", d)
                assertEquals(listOf(key1, key2), loadCalls[0])
                assertEquals(listOf(key4), loadCalls[1])
            }
        }

        @Test
        fun testComplexBatchingWithNesting() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, loadCalls) = trackableLoader<String>(TestDispatchStrategy.BATCH)

                listOf(
                    launch { loader.load("A") },
                    launch {
                        launch {
                            launch { loader.load("B") }
                            launch {
                                launch { loader.load("C") }
                                launch {
                                    launch { loader.load("D") }
                                }
                            }
                        }
                    }
                ).joinAll()

                assertEquals(1, loadCalls.size) // just one batch
                // all the calls should be in the same batch
                assertEquals(listOf("A", "B", "C", "D"), loadCalls[0])
            }
        }

        @Test
        fun testCallingLoadersFromLoaders() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val deepLoadCalls = mutableListOf<List<List<String>>>()
                val deepLoader =
                    createLoader<List<String>, List<String>>(TestDispatchStrategy.BATCH) { keys ->
                        deepLoadCalls.add(keys)
                        keys.associate { it to it }
                    }

                val aLoadCalls = mutableListOf<List<String>>()
                val aLoader =
                    createLoader<String, String>(TestDispatchStrategy.BATCH) { keys ->
                        aLoadCalls.add(keys)
                        (deepLoader.load(keys) ?: listOf()).associate { it to it }
                    }

                val bLoadCalls = mutableListOf<List<String>>()
                val bLoader =
                    createLoader<String, String>(TestDispatchStrategy.BATCH) { keys ->
                        bLoadCalls.add(keys)
                        (deepLoader.load(keys) ?: listOf()).associate { it to it }
                    }

                val (a1, b1, a2, b2) =
                    listOf(
                        async { aLoader.load("A1") },
                        async { bLoader.load("B1") },
                        async { aLoader.load("A2") },
                        async { bLoader.load("B2") }
                    ).awaitAll()

                assertEquals("A1", a1)
                assertEquals("B1", b1)
                assertEquals("A2", a2)
                assertEquals("B2", b2)

                assertEquals(1, aLoadCalls.size) // just one batch
                assertEquals(listOf("A1", "A2"), aLoadCalls[0])

                assertEquals(1, bLoadCalls.size) // just one batch
                assertEquals(listOf("B1", "B2"), bLoadCalls[0])

                assertEquals(1, deepLoadCalls.size) // just one batch
                val sortedDeepLoadCalls = deepLoadCalls[0].sortedBy { it[0] }

                assertEquals(listOf("A1", "A2"), sortedDeepLoadCalls[0])
                assertEquals(listOf("B1", "B2"), sortedDeepLoadCalls[1])
            }
        }

        inner class ResolverContainer(
            private val loader: InternalDataLoader<String, String, Any>,
            private val coroutineScope: CoroutineScope
        ) {
            suspend fun resolver1() =
                coroutineScope.future {
                    loader.load("A")
                }

            suspend fun resolver2() =
                coroutineScope.future {
                    loader.load("B")
                }

            suspend fun runResolverAsync(fn: suspend () -> CompletableFuture<String?>): String? = fn().await()
        }

        @Test
        fun testBatchingWithFuture() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, loadCalls) = trackableLoader<String>(TestDispatchStrategy.BATCH)

                val resolverContainer =
                    ResolverContainer(
                        loader,
                        this
                    )

                val resolvers =
                    listOf(
                        resolverContainer::resolver1,
                        resolverContainer::resolver2
                    )
                val deferreds = mutableListOf<Deferred<String?>>()
                for (resolver in resolvers) {
                    val deferred = async { resolverContainer.runResolverAsync(resolver) }
                    deferreds.add(deferred)
                }

                val values = deferreds.awaitAll()
                assertEquals(listOf("A", "B"), values)
                assertEquals(listOf("A", "B"), loadCalls[0])
            }
        }

        @Test
        fun testUsageFromSuspendableFunctions() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, loadCalls) = trackableLoader<String>(TestDispatchStrategy.BATCH)

                suspend fun loadElement(key: String): String? = loader.load(key)

                listOf(
                    launch {
                        loadElement("A")
                    },
                    launch {
                        loadElement("B")
                    },
                    launch {
                        launch {
                            launch {
                                loadElement("C")
                            }
                        }
                    }
                ).joinAll()

                assertEquals(listOf("A", "B", "C"), loadCalls[0])
            }
        }
    }

    /**
     * This test is less about the expected result and more about making sure there are no strange exceptions that get thrown by the instrumentation
     * when we have a lot of concurrent loads happening.
     * This test creates a list of keys, some of which are duplicated, and then tries to load all of them in a highly concurrent manner. This should
     * exercise any strange concurrency bugs.
     *
     * Cannot use @ParameterizedTest here since @RepeatedTest is used.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @RepeatedTest(100)
    fun testConcurrentLoadsWithSomeDuplicateKeys() {
        testDispatchStrategies.forEach { testDispatchStrategy ->
            runBlocking(realNextTickDispatcher()) {
                val (loader, _) = trackableLoader<Int>(testDispatchStrategy)
                val keys: List<Int> = (1..5).map { it }
                val awaitResults: List<Deferred<Int?>> = keys
                    .map { k ->
                        (1..100).parallelMap(200, 200) { async { loader.load(k) } }.toList()
                    }.flatten()
                val expected = keys
                    .map { k ->
                        (1..100).map { k }
                    }.flatten()
                val results = awaitResults.awaitAll()
                assertEquals(expected, results, "No exceptions should be thrown when lots of concurrent loads take place")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TestDispatchStrategy::class)
    fun testFailedDispatch(testDispatchStrategy: TestDispatchStrategy) {
        runBlocking(singleThreadedNextTickDispatcher()) {
            val expectedException = RuntimeException("Something went wrong")
            val loader = createLoader<Int, Int>(testDispatchStrategy) { throw expectedException }

            supervisorScope {
                val actualException =
                    runCatching {
                        listOf(async { loader.load(1) }, async { loader.load(2) }).awaitAll()
                    }.exceptionOrNull()
                assertEquals(
                    expectedException.message,
                    actualException!!.message
                )
                assertEquals(
                    expectedException::class,
                    actualException::class
                )
            }
        }
    }

    @ParameterizedTest
    @EnumSource(TestDispatchStrategy::class)
    fun testCoalescingOfIdenticalRequests(testDispatchStrategy: TestDispatchStrategy) {
        runBlocking(singleThreadedNextTickDispatcher()) {
            val (loader, loadCalls) = trackableLoader<Int>(testDispatchStrategy)
            val result1Def = async { loader.load(1) }
            val result2Def = async { loader.load(1) }

            val results = listOf(result1Def, result2Def).awaitAll()

            assertEquals(listOf(1, 1), results)
            assertEquals(listOf(1), loadCalls[0])
        }
    }

    @Nested
    inner class BatchInstrumentationTest {
        lateinit var mockOnComplete: DataLoaderInstrumentation.OnCompleteLoad
        lateinit var instrumentation: DataLoaderInstrumentation

        open inner class TestInst(
            private val onComplete: DataLoaderInstrumentation.OnCompleteLoad
        ) : DataLoaderInstrumentation {
            override fun <K> beginLoad(
                key: K,
                keyContext: Any?,
                batchState: DataLoaderInstrumentation.BatchState,
            ): DataLoaderInstrumentation.OnCompleteLoad = onComplete
        }

        @BeforeEach
        fun setup() {
            mockOnComplete = mockk<DataLoaderInstrumentation.OnCompleteLoad>(relaxed = true)
            instrumentation = spyk(TestInst(mockOnComplete))
        }

        @Test
        fun testInstrumentationSingleLoad() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, _) = trackableLoader<String>(TestDispatchStrategy.BATCH, batchInstrumentationProvider = Provider { instrumentation })
                val a = loader.load("A")
                assertEquals("A", a)

                verify { instrumentation.createBatchState() }
                verify { instrumentation.beginLoad(eq("A"), isNull(), any<DataLoaderInstrumentation.BatchState>()) }

                verify {
                    instrumentation.onAddBatchEntry(
                        eq("A"),
                        isNull(),
                        any<DataLoaderInstrumentation.BatchState>()
                    )
                }
                coVerify { instrumentation.instrumentBatchLoad<String, String>(any(), any<DataLoaderInstrumentation.BatchState>()) }
                verify { mockOnComplete.onComplete(any<String>(), isNull(), any()) }
            }
        }

        @Test
        fun testInstrumentationMultipleLoad() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, _) = trackableLoader<String>(TestDispatchStrategy.BATCH, batchInstrumentationProvider = Provider { instrumentation })
                val a = loader.load("A")
                assertEquals("A", a)
                verify { instrumentation.beginLoad(eq("A"), isNull(), any<DataLoaderInstrumentation.BatchState>()) }

                verify { instrumentation.createBatchState() }
                verify {
                    instrumentation.onAddBatchEntry(
                        eq("A"),
                        isNull(),
                        any<DataLoaderInstrumentation.BatchState>()
                    )
                }
                coVerify { instrumentation.instrumentBatchLoad<String, String>(any(), any<DataLoaderInstrumentation.BatchState>()) }
                verify { mockOnComplete.onComplete(eq("A"), isNull(), any()) }

                clearMocks(instrumentation, mockOnComplete)
                val b = loader.load("B")
                assertEquals("B", b)

                verify { instrumentation.beginLoad(eq("B"), isNull(), any<DataLoaderInstrumentation.BatchState>()) }

                verify { instrumentation.createBatchState() }
                verify {
                    instrumentation.onAddBatchEntry(
                        eq("B"),
                        isNull(),
                        any<DataLoaderInstrumentation.BatchState>()
                    )
                }
                coVerify { instrumentation.instrumentBatchLoad<String, String>(any(), any<DataLoaderInstrumentation.BatchState>()) }
                verify { mockOnComplete.onComplete(eq("B"), isNull(), any()) }
            }
        }

        @Test
        fun testInstrumentationCachedLoad() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, _) = trackableLoader<String>(TestDispatchStrategy.BATCH, batchInstrumentationProvider = Provider { instrumentation })
                val a = loader.load("A")

                assertEquals("A", a)
                verify { instrumentation.beginLoad(eq("A"), isNull(), any<DataLoaderInstrumentation.BatchState>()) }

                verify { instrumentation.createBatchState() }
                verify {
                    instrumentation.onAddBatchEntry(
                        eq("A"),
                        isNull(),
                        any<DataLoaderInstrumentation.BatchState>()
                    )
                }
                coVerify { instrumentation.instrumentBatchLoad<String, String>(any(), any<DataLoaderInstrumentation.BatchState>()) }
                verify { mockOnComplete.onComplete(any<String>(), isNull(), any()) }

                clearMocks(instrumentation, mockOnComplete)
                val a2 = loader.load("A")
                assertEquals("A", a2)
                verify { instrumentation.beginLoad(eq("A"), isNull(), any<DataLoaderInstrumentation.BatchState>()) }
                verify { mockOnComplete.onComplete(any<String>(), isNull(), any()) }
            }
        }

        @Test
        fun testConcurrentLoadsDifferentKeys() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, _) = trackableLoader<String>(
                    TestDispatchStrategy.BATCH,
                    batchInstrumentationProvider = Provider { instrumentation }
                )
                val (a, b) =
                    listOf(
                        async { loader.load("A") },
                        async { loader.load("B") }
                    ).awaitAll()
                assertEquals("A", a)
                assertEquals("B", b)

                verify { instrumentation.beginLoad(eq("A"), isNull(), any<DataLoaderInstrumentation.BatchState>()) }
                verify { instrumentation.beginLoad(eq("B"), isNull(), any<DataLoaderInstrumentation.BatchState>()) }

                verify { instrumentation.createBatchState() }
                verify { instrumentation.onAddBatchEntry(eq("A"), isNull(), any()) }
                verify { instrumentation.onAddBatchEntry(eq("B"), isNull(), any()) }

                coVerify { instrumentation.instrumentBatchLoad<String, String>(any(), any()) }
                verify { mockOnComplete.onComplete(eq("A"), isNull(), any()) }
                verify { mockOnComplete.onComplete(eq("B"), isNull(), any()) }
            }
        }

        @Test
        fun testConcurrentLoadsSameKeys() {
            runBlocking(singleThreadedNextTickDispatcher()) {
                val (loader, _) = trackableLoader<String>(
                    TestDispatchStrategy.BATCH,
                    batchInstrumentationProvider = Provider { instrumentation }
                )
                val (a, a2) =
                    listOf(
                        async { loader.load("A") },
                        async { loader.load("A") }
                    ).awaitAll()

                assertEquals("A", a)
                assertEquals("A", a2)

                verify(exactly = 2) { instrumentation.beginLoad(eq("A"), any(), any<DataLoaderInstrumentation.BatchState>()) }

                verify { instrumentation.createBatchState() }
                verify { instrumentation.onAddBatchEntry(eq("A"), any(), any<DataLoaderInstrumentation.BatchState>()) }

                coVerify { instrumentation.instrumentBatchLoad<String, String>(any(), any<DataLoaderInstrumentation.BatchState>()) }
                verify(exactly = 2) { mockOnComplete.onComplete(eq("A"), isNull(), any()) }
            }
        }
    }

    private suspend fun <K : Any, V> createLoader(
        testDispatchStrategy: TestDispatchStrategy,
        maxBatchSize: Int = 1000,
        loaderFn: suspend (
            keys: List<K>
        ) -> Map<K, V?>
    ): InternalDataLoader<K, V, Any> {
        val dispatchStrategy = createDispatchStrategy(
            testDispatchStrategy,
            object : MappedBatchLoadFn<K, V> {
                override suspend fun load(
                    keys: Set<K>,
                    env: BatchLoaderEnvironment<K>
                ): Map<K, V?> = loaderFn(keys.toList())
            },
            DataLoaderOptions(maxBatchSize),
            object : DataLoaderInstrumentation {}
        )

        return InternalDataLoader.newLoader(
            dispatchStrategy,
        )
    }

    private suspend fun <K : Any> trackableLoader(
        testDispatchStrategy: TestDispatchStrategy,
        maxBatchSize: Int = 1000,
        batchInstrumentationProvider: Provider<DataLoaderInstrumentation>? = null
    ): Pair<InternalDataLoader<K, K, Any>, CopyOnWriteArrayList<List<K>>> {
        val loadCalls = CopyOnWriteArrayList<List<K>>()
        val identityLoader =
            createInstrumentedLoader(
                testDispatchStrategy,
                maxBatchSize,
                { keys: List<K> ->
                    loadCalls.add(keys)
                    keys.associateWith { it }
                },
                batchInstrumentationProvider
            )
        return Pair(identityLoader, loadCalls)
    }

    private suspend fun <K : Any, V> createInstrumentedLoader(
        testDispatchStrategy: TestDispatchStrategy,
        maxBatchSize: Int = 1000,
        loaderFn: suspend (
            keys: List<K>
        ) -> Map<K, V?>,
        batchInstrumentationProvider: Provider<DataLoaderInstrumentation>?
    ): InternalDataLoader<K, V, Any> {
        val internalDispatchStrategy = createDispatchStrategy(
            testDispatchStrategy,
            object : MappedBatchLoadFn<K, V> {
                override suspend fun load(
                    keys: Set<K>,
                    env: BatchLoaderEnvironment<K>
                ): Map<K, V?> = loaderFn(keys.toList())
            },
            DataLoaderOptions(maxBatchSize),
            batchInstrumentationProvider?.get() ?: object : DataLoaderInstrumentation {},
        )

        return InternalDataLoader.newLoader(internalDispatchStrategy)
    }

    private fun <K, V> createDispatchStrategy(
        testDispatchStrategy: TestDispatchStrategy,
        loadFn: MappedBatchLoadFn<K, V>,
        dataLoaderOptions: DataLoaderOptions,
        instrumentation: DataLoaderInstrumentation
    ): InternalDispatchStrategy<K, V> =
        when (testDispatchStrategy) {
            TestDispatchStrategy.BATCH -> InternalDispatchStrategy.batchDispatchStrategy(
                loadFn.genericBatchLoadFn(),
                NextTickScheduleFn,
                dataLoaderOptions,
                instrumentation,
            )

            TestDispatchStrategy.IMMEDIATE -> InternalDispatchStrategy.immediateDispatchStrategy(
                loadFn.genericBatchLoadFn(),
                instrumentation,
            )
        }
}
