package viaduct.dataloader

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Suppress("TooGenericExceptionCaught")
internal class ThreadSafeInternalDataLoader<K : Any, V, C : Any> internal constructor(
    private val internalDispatchStrategy: InternalDispatchStrategy<K, V>,
    private val cacheKeyFn: CacheKeyFn<K, C>,
    private val cacheKeyMatchFn: CacheKeyMatchFn<C>? = null,
) : InternalDataLoader<K, V, C> {
    private val cacheMap: ConcurrentHashMap<C, InternalDataLoader.Batch.BatchResult<V?>> = ConcurrentHashMap()
    private val instrumentation = internalDispatchStrategy.instrumentation

    override suspend fun loadMany(
        keys: List<K>,
        keyContexts: List<Any?>?
    ): List<V?> =
        coroutineScope {
            val kcs = keyContexts ?: keys.map { null }
            keys
                .mapIndexed { i, k ->
                    val keyContext = kcs.getOrNull(i)
                    async {
                        load(k, keyContext)
                    }
                }.awaitAll()
        }

    override suspend fun load(
        key: K,
        keyContext: Any?
    ): V? {
        val cacheKey = cacheKeyFn(key)
        val defaultResult = InternalDataLoader.Batch.BatchResult(CompletableDeferred<V?>())
        val entryResult =
            cacheKeyMatchFn?.let { matchFn ->
                cacheMap
                    .searchKeys(50) { existingKey ->
                        if (matchFn(cacheKey, existingKey)) existingKey else null
                    }?.let { matchedKey -> cacheMap.get(matchedKey) }
            } ?: cacheMap.computeIfAbsent(cacheKey) { defaultResult }

        val isCached = entryResult != defaultResult
        if (!isCached) {
            internalDispatchStrategy.scheduleResult(key, keyContext, entryResult) { keys, _ -> failedDispatch(keys) }
        }

        // getBatchState awaits on the Deferred<BatchState> within the entryResult because there is a chance that we try to get the batchState while
        // the entry is still being added to the batch. If the result gets put into the cacheMap, and then another load is called with the same key,
        // it will skip the addEntryToBatch section, but the batchState may not have been added to the result yet. Awaiting-ing on the batchState
        // ensures that there is a batchState value present.
        val batchState = entryResult.batchState.await()
        val instContext = instrumentation.beginLoad(key, keyContext, batchState)

        return runCatching {
            entryResult.await()
        }.also { r ->
            instContext.onComplete(r.getOrNull(), r.exceptionOrNull(), batchState)
        }.getOrThrow()
    }

    override fun clear(key: K) {
        val cacheKey = cacheKeyFn(key)
        cacheMap.remove(cacheKey)
    }

    override fun clearAll() {
        cacheMap.clear()
    }

    private fun failedDispatch(keys: List<K>) {
        keys.forEach { k -> this.clear(k) }
    }
}
