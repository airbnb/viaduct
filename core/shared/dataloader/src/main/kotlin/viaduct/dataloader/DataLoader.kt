package viaduct.dataloader

import javax.inject.Provider

/**
 * This is the base for all data loaders with some dispatching mechanism
 *
 * By default, it uses [NextTickDispatcher] to dispatch the loads
 * Override [batchScheduleFn] if you have a different dispatching mechanism
 */
abstract class DataLoader<K : Any, V, C : Any> {
    data class DataLoaderInfo(
        val loaderName: String,
        val loaderClass: String,
    )

    protected open val loaderInfo = DataLoaderInfo(
        loaderName = this::class.simpleName ?: "unknown",
        loaderClass = this::class.qualifiedName ?: "unknown"
    )

    // Override for instrumentation implementation - Default implementation no-ops
    protected open val dataLoaderInstrumentationProvider: Provider<DataLoaderInstrumentation> =
        Provider {
            object : DataLoaderInstrumentation {}
        }

    protected val internalDataLoader by lazy {
        val loadFn = GenericBatchLoadFn<K, V> { keys, env ->
            val keySet = keys.toSet()
            statsCollector?.logDefaultLoad(loaderInfo, keySet, env.dispatchingContext)
            statsCollector?.logTotalKeyCount(loaderInfo, env.totalKeyCount)
            val resultMap = internalLoadWithTry(keySet, env)
            // Convert map to list in the same order as keys
            keys.map { key -> resultMap[key] ?: Try(value = null) }
        }

        val dispatchStrategy = getInternalDispatchStrategy(loadFn)

        InternalDataLoader.newLoader(
            dispatchStrategy,
            cacheKeyFn,
            cacheKeyMatchFn,
        )
    }

    protected fun logActualLoads(
        keys: Collection<Any>,
        dispatchingContext: DispatchingContext
    ) {
        statsCollector?.logActualLoads(loaderInfo, keys, dispatchingContext)
    }

    protected open fun getDataLoaderOptions(): DataLoaderOptions = DataLoaderOptions()

    /**
     * Default to the mutex batch
     */
    private fun getInternalDispatchStrategy(batchLoadFn: GenericBatchLoadFn<K, V>): InternalDispatchStrategy<K, V> =
        if (shouldUseImmediateDispatch()) {
            createImmediateDispatchStrategy(batchLoadFn)
        } else {
            createBatchDispatchStrategy(batchLoadFn)
        }

    /**
     * Controls the dispatch strategy for this data loader.
     *
     * When false (default): Uses batch dispatch - collects multiple load requests and executes them together
     * When true: Uses immediate dispatch - executes each load request individually (caching only, no batching)
     */
    protected open fun shouldUseImmediateDispatch(): Boolean = false

    private fun createImmediateDispatchStrategy(batchLoadFn: GenericBatchLoadFn<K, V>): InternalDispatchStrategy<K, V> {
        val instrumentedBatchLoadFn = GenericBatchLoadFn { keys, env ->
            val startTimeNs = System.nanoTime()
            statsCollector?.logActualLoads(loaderInfo, listOf(keys), env.dispatchingContext)
            batchLoadFn.load(keys, env).also {
                statsCollector?.logLoadTotalLatency(loaderInfo, System.nanoTime() - startTimeNs, 0L, env.dispatchingContext)
            }
        }
        return InternalDispatchStrategy.immediateDispatchStrategy(
            instrumentedBatchLoadFn,
            dataLoaderInstrumentationProvider.get(),
            getDataLoaderOptions()
        )
    }

    private fun createBatchDispatchStrategy(batchLoadFn: GenericBatchLoadFn<K, V>): InternalDispatchStrategy<K, V> =
        InternalDispatchStrategy.batchDispatchStrategy(
            batchLoadFn,
            { block ->
                val start = System.nanoTime()
                batchScheduleFn { dispatchingContext ->
                    val delayNs = System.nanoTime() - start
                    statsCollector?.logLoadScheduleLatency(loaderInfo, delayNs, dispatchingContext)
                    block(dispatchingContext)
                    statsCollector?.logLoadTotalLatency(loaderInfo, System.nanoTime() - start, delayNs, dispatchingContext)
                }
            },
            getDataLoaderOptions(),
            dataLoaderInstrumentationProvider.get()
        )

    protected open val statsCollector: DataLoaderStatsCollector? = null

    /**
     * Override this method OR [internalLoadWithTry] to implement your loader.
     *
     * For simple loaders, override this method to return values directly.
     * For loaders that need per-key error handling, override [internalLoadWithTry] instead.
     */
    protected open suspend fun internalLoad(
        keys: Set<K>,
        environment: BatchLoaderEnvironment<K>
    ): Map<K, V?> {
        throw NotImplementedError(
            "Override either internalLoad() or internalLoadWithTry() in ${this::class.qualifiedName}"
        )
    }

    /**
     * Override this method to return per-key errors via [Try.error].
     *
     * By default, this wraps [internalLoad] results in [Try] and catches any exception,
     * applying it to all keys (existing behavior).
     *
     * Override this to handle errors per-key. For example, if loading key A fails but key B succeeds,
     * return `mapOf(A to Try(error = exception), B to Try(value = result))`.
     *
     * When combined with [DataLoaderOptions.cachingExceptionsEnabled] = false, per-key errors
     * will not be cached and subsequent loads will re-fetch from the batch loader.
     */
    @Suppress("TooGenericExceptionCaught")
    protected open suspend fun internalLoadWithTry(
        keys: Set<K>,
        environment: BatchLoaderEnvironment<K>
    ): Map<K, Try<V>> {
        return try {
            internalLoad(keys, environment).mapValues { Try(it.value) }
        } catch (e: Exception) {
            keys.associateWith { Try(error = e) }
        }
    }

    protected open val batchScheduleFn: DispatchScheduleFn = NextTickScheduleFn

    /**
     * Set this to override the default cache key (loader key)
     */
    @Suppress("UNCHECKED_CAST")
    protected open val cacheKeyFn: CacheKeyFn<K, C> = { k: K -> k as C }

    /**
     * Set this to override the default cache lookup behavior (exact key match).
     *
     * Returns a boolean indicating whether the new cache key (first argument) matches the
     * existing cache key (second argument), so that the existing cache key's value can be
     * used for the new cache key
     */
    protected open val cacheKeyMatchFn: CacheKeyMatchFn<C>? = null
}
