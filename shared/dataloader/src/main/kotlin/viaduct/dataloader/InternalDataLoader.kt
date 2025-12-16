package viaduct.dataloader

import javax.inject.Provider
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred

/**
 * Core InternalDataLoader interface.
 *
 * The companion object of this interface provides factory functions for creating loaders.
 */
interface InternalDataLoader<K : Any, V, C : Any> {
    suspend fun loadMany(
        keys: List<K>,
        keyContexts: List<Any?>? = null
    ): List<V?>

    suspend fun load(
        key: K,
        keyContext: Any? = null
    ): V?

    fun clear(key: K)

    fun clearAll()

    interface Batch<K, V, E : Batch.Entry<K, V>> {
        suspend fun addNewEntry(
            key: K,
            keyContext: Any?,
            result: BatchResult<V?>
        ): Boolean

        suspend fun dispatch(
            batchLoadFn: GenericBatchLoadFn<K, V>,
            dispatchingContext: DispatchingContext,
            onFailedDispatch: (keys: List<K>, throwable: Throwable) -> Unit
        )

        class BatchResult<V>(
            private val deferred: CompletableDeferred<V>,
        ) : CompletableDeferred<V> by deferred {
            val batchState = CompletableDeferred<DataLoaderInstrumentation.BatchState>()
        }

        interface Entry<K, V> {
            val key: K
            val keyContext: Any?
            val result: BatchResult<V?>
        }
    }

    companion object {
        fun <K, V> MappedBatchLoadFn<K, V>.genericBatchLoadFn(): GenericBatchLoadFn<K, V> {
            val baseLoadFn = this
            return object : GenericBatchLoadFn<K, V> {
                override suspend fun load(
                    keys: List<K>,
                    env: BatchLoaderEnvironment<K>
                ): List<Try<V?>> {
                    @Suppress("TooGenericExceptionCaught")
                    return try {
                        baseLoadFn.load(keys.toSet(), env).let { resultMap -> keys.map { resultMap[it] } }.map { Try(it) }
                    } catch (e: Exception) {
                        List(keys.size) { Try(error = e) }
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        fun <K : Any, V, C : Any> newMappedLoader(
            fn: MappedBatchLoadFn<K, V>,
            batchScheduleFn: DispatchScheduleFn = NextTickScheduleFn,
            dataLoaderOptions: DataLoaderOptions = DataLoaderOptions(1000),
            cacheKeyFn: CacheKeyFn<K, C> = { k: K -> k as C },
            cacheKeyMatchFn: CacheKeyMatchFn<C>? = null,
            dataLoaderInstrumentationProvider: Provider<DataLoaderInstrumentation>? = null,
        ): InternalDataLoader<K, V, C> =
            createInternalDataLoader(
                InternalDispatchStrategy.batchDispatchStrategy(
                    fn.genericBatchLoadFn(),
                    batchScheduleFn,
                    dataLoaderOptions,
                    dataLoaderInstrumentationProvider?.get() ?: object : DataLoaderInstrumentation {}
                ),
                cacheKeyFn,
                cacheKeyMatchFn
            )

        @Suppress("UNCHECKED_CAST")
        internal fun <K : Any, V, C : Any> newLoader(
            internalDispatchStrategy: InternalDispatchStrategy<K, V>,
            cacheKeyFn: CacheKeyFn<K, C> = { k: K -> k as C },
            cacheKeyMatchFn: CacheKeyMatchFn<C>? = null,
        ): InternalDataLoader<K, V, C> = createInternalDataLoader(internalDispatchStrategy, cacheKeyFn, cacheKeyMatchFn)

        private fun <K : Any, V, C : Any> createInternalDataLoader(
            internalDispatchStrategy: InternalDispatchStrategy<K, V>,
            cacheKeyFn: CacheKeyFn<K, C>,
            cacheKeyMatchFn: CacheKeyMatchFn<C>?,
        ): InternalDataLoader<K, V, C> {
            // default to thread safe loader
            return ThreadSafeInternalDataLoader(
                internalDispatchStrategy,
                cacheKeyFn,
                cacheKeyMatchFn,
            )
        }
    }
}

fun interface GenericBatchLoadFn<K, V> {
    suspend fun load(
        keys: List<K>,
        env: BatchLoaderEnvironment<K>
    ): List<Try<V?>>
}

data class BatchLoaderEnvironment<K>(
    val context: Any? = null,
    val keyContexts: Map<K, Any?> = mapOf(),
    // For all keys that are loaded in the same batch, including the ones that are cached
    val totalKeyCount: Int,
    val dispatchingContext: DispatchingContext
)

data class Try<V>(
    val value: V? = null,
    val error: Throwable? = null
)

data class DataLoaderOptions(
    val maxBatchSize: Int = 1000
)

interface BatchLoadFn<K, V> {
    suspend fun load(
        keys: List<K>,
        env: BatchLoaderEnvironment<K>
    ): List<V?>
}

interface BatchLoadWithTryFn<K, V> {
    suspend fun load(
        keys: List<K>,
        env: BatchLoaderEnvironment<K>
    ): List<Try<V?>>
}

interface MappedBatchLoadFn<K, V> {
    suspend fun load(
        keys: Set<K>,
        env: BatchLoaderEnvironment<K>
    ): Map<K, V?>
}

interface MappedBatchLoadWithTryFn<K, V> {
    suspend fun load(
        keys: Set<K>,
        env: BatchLoaderEnvironment<K>
    ): Map<K, Try<V?>>
}

interface DispatchingContext

typealias DispatchScheduleFn =
    suspend (fn: suspend (dispatchingContext: DispatchingContext) -> Unit) -> Unit

val NextTickScheduleFn: DispatchScheduleFn =
    { fn ->
        nextTick(coroutineContext) { dispatchingContext ->
            fn.invoke(dispatchingContext)
        }
    }

typealias CacheKeyFn<K, C> = (K) -> C

/**
 * Used to override the default cache lookup behavior (exact key match).
 *
 * Returns a boolean indicating whether the new cache key (first argument) matches the
 * existing cache key (second argument), so that the existing cache key's value can be
 * used for the new cache key
 */
typealias CacheKeyMatchFn<C> = (C, C) -> Boolean
