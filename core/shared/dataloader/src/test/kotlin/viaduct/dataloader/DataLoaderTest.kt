@file:Suppress("ForbiddenImport")

package viaduct.dataloader

import io.mockk.mockk
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.service.api.spi.FlagManager

class DataLoaderTest {
    private fun nextTickDispatcher() =
        NextTickDispatcher(
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            Executors.newSingleThreadExecutor().asCoroutineDispatcher(),
            flagManager = FlagManager.disabled
        )

    private val dataLoaderStatsCollectorMock = mockk<DataLoaderStatsCollector>(relaxed = true)

    private class TestDataLoader : DataLoader<String, String, String>() {
        override suspend fun internalLoad(
            keys: Set<String>,
            environment: BatchLoaderEnvironment<String>
        ): Map<String, String?> = keys.associateWith { it }

        suspend fun loadByKeys(keys: List<String>) = internalDataLoader.loadMany(keys, listOf())

        suspend fun loadByKey(key: String) = internalDataLoader.load(key)
    }

    private class TestDataLoaderWithCollector(
        dataLoaderStatsCollectorMock: DataLoaderStatsCollector
    ) : DataLoader<String, String, String>() {
        override suspend fun internalLoad(
            keys: Set<String>,
            environment: BatchLoaderEnvironment<String>
        ): Map<String, String?> = keys.associateWith { it }

        suspend fun loadByKeys(keys: List<String>): List<String?> {
            logActualLoads(keys, NextTickDispatchingContext(0))
            return internalDataLoader.loadMany(keys, listOf())
        }

        suspend fun loadByKey(key: String): String? {
            logActualLoads(listOf(key), NextTickDispatchingContext(0))
            return internalDataLoader.load(key)
        }

        override val statsCollector: DataLoaderStatsCollector = dataLoaderStatsCollectorMock
    }

    private class TestDataLoaderWithCacheKeyOverrides : DataLoader<Map<String, Any?>, String, String>() {
        override suspend fun internalLoad(
            keys: Set<Map<String, Any?>>,
            environment: BatchLoaderEnvironment<Map<String, Any?>>
        ): Map<Map<String, Any?>, String?> = keys.associateWith { it["value"] as String }

        suspend fun loadByKeys(keys: List<Map<String, Any?>>): List<String?> {
            logActualLoads(keys, NextTickDispatchingContext(0))
            return internalDataLoader.loadMany(keys, listOf())
        }

        suspend fun loadByKey(key: Map<String, Any?>): String? {
            logActualLoads(listOf(key), NextTickDispatchingContext(0))
            return internalDataLoader.load(key)
        }

        override val cacheKeyFn: CacheKeyFn<Map<String, Any?>, String> = { key -> "${key["id"]}:${key["fields"]}" }
        override val cacheKeyMatchFn: CacheKeyMatchFn<String>? = { newKey, existingKey ->
            val newParts = newKey.split(":")
            val oldParts = existingKey.split(":")
            newParts[0] == oldParts[0] && oldParts[1].contains(newParts[1])
        }
    }

    @Test
    fun testLoadMany() =
        runBlocking(nextTickDispatcher()) {
            val result = TestDataLoader().loadByKeys(listOf("foo", "bar"))
            assertEquals(listOf("foo", "bar"), result)
        }

    @Test
    fun testLoadByKey() =
        runBlocking(nextTickDispatcher()) {
            val result = TestDataLoader().loadByKey("foo")
            assertEquals("foo", result)
        }

    @Test
    fun testStatsCollector() =
        runBlocking(nextTickDispatcher()) {
            val result = TestDataLoaderWithCollector(dataLoaderStatsCollectorMock).loadByKey("foo")
            assertEquals("foo", result)
            verify(exactly = 1) { dataLoaderStatsCollectorMock.logTotalKeyCount(any(), eq(1)) }
            verify(exactly = 1) {
                dataLoaderStatsCollectorMock.logActualLoads(
                    eq(
                        DataLoader.DataLoaderInfo(
                            loaderName = "TestDataLoaderWithCollector",
                            loaderClass = TestDataLoaderWithCollector::class.qualifiedName!!,
                        )
                    ),
                    eq(listOf("foo")),
                    eq(NextTickDispatchingContext(0))
                )
            }
        }

    @Test
    fun testCacheKeyOverrides() =
        runBlocking(nextTickDispatcher()) {
            val loader = TestDataLoaderWithCacheKeyOverrides()
            assertEquals("foo", loader.loadByKey(mapOf("id" to 1, "fields" to "a,b", "value" to "foo")))
            assertEquals("bar", loader.loadByKey(mapOf("id" to 2, "fields" to "a,b", "value" to "bar")))
            // Should not be "baz" since there should be a cache key match based on the overridden cacheKeyMatchFn
            assertEquals("foo", loader.loadByKey(mapOf("id" to 1, "fields" to "b", "value" to "baz")))
        }

    private class TestDataLoaderWithPerKeyErrors(
        private val loadCalls: CopyOnWriteArrayList<Set<String>> = CopyOnWriteArrayList(),
        private val errorKeys: Set<String> = emptySet()
    ) : DataLoader<String, String, String>() {
        override suspend fun internalLoadWithTry(
            keys: Set<String>,
            environment: BatchLoaderEnvironment<String>
        ): Map<String, Try<String>> {
            loadCalls.add(keys)
            return keys.associateWith { key ->
                if (key in errorKeys) {
                    Try(error = RuntimeException("Error for $key"))
                } else {
                    Try(value = key)
                }
            }
        }

        override fun getDataLoaderOptions() = DataLoaderOptions(cachingExceptionsEnabled = false)

        suspend fun loadByKey(key: String) = internalDataLoader.load(key)

        fun getLoadCalls() = loadCalls.toList()
    }

    @Nested
    inner class InternalLoadWithTryTests {
        @Test
        fun testInternalLoadWithTryAllowsPerKeyErrors() =
            runBlocking(nextTickDispatcher()) {
                val loader = TestDataLoaderWithPerKeyErrors(errorKeys = setOf("ERROR"))

                val successResult = loader.loadByKey("SUCCESS")
                assertEquals("SUCCESS", successResult)

                supervisorScope {
                    val errorResult = runCatching { loader.loadByKey("ERROR") }
                    assertTrue(errorResult.isFailure)
                    assertEquals("Error for ERROR", errorResult.exceptionOrNull()?.message)
                }
            }

        @Test
        fun testPerKeyErrorsNotCachedWhenDisabled() =
            runBlocking(nextTickDispatcher()) {
                val loadCalls = CopyOnWriteArrayList<Set<String>>()
                val loader = TestDataLoaderWithPerKeyErrors(loadCalls, errorKeys = setOf("ERROR"))

                supervisorScope {
                    runCatching { loader.loadByKey("ERROR") }
                }
                assertEquals(1, loadCalls.size)

                // Second load should trigger new load (error not cached)
                supervisorScope {
                    runCatching { loader.loadByKey("ERROR") }
                }
                assertEquals(2, loadCalls.size)
            }

        @Test
        fun testSuccessfulResultsStillCachedWhenExceptionCachingDisabled() =
            runBlocking(nextTickDispatcher()) {
                val loadCalls = CopyOnWriteArrayList<Set<String>>()
                val loader = TestDataLoaderWithPerKeyErrors(loadCalls)

                val result1 = loader.loadByKey("A")
                assertEquals("A", result1)
                assertEquals(1, loadCalls.size)

                val result2 = loader.loadByKey("A")
                assertEquals("A", result2)
                assertEquals(1, loadCalls.size)
            }
    }
}
