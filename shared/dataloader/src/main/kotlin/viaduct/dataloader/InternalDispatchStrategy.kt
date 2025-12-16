package viaduct.dataloader

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

interface InternalDispatchStrategy<K, V> {
    companion object {
        fun <K, V> batchDispatchStrategy(
            batchLoadFn: GenericBatchLoadFn<K, V>,
            batchScheduleFn: DispatchScheduleFn,
            dataLoaderOptions: DataLoaderOptions,
            instrumentation: DataLoaderInstrumentation,
        ): InternalDispatchStrategy<K, V> =
            BatchDispatchStrategy(
                batchLoadFn,
                batchScheduleFn,
                dataLoaderOptions,
                instrumentation
            )

        fun <K, V> immediateDispatchStrategy(
            batchLoadFn: GenericBatchLoadFn<K, V>,
            instrumentation: DataLoaderInstrumentation,
        ): InternalDispatchStrategy<K, V> =
            ImmediateDispatchStrategy(
                batchLoadFn,
                instrumentation
            )
    }

    val instrumentation: DataLoaderInstrumentation

    suspend fun scheduleResult(
        key: K,
        keyContext: Any?,
        result: InternalDataLoader.Batch.BatchResult<V?>,
        onFailedDispatch: (keys: List<K>, throwable: Throwable) -> Unit
    )
}

internal class BatchDispatchStrategy<K, V>(
    private val batchLoadFn: GenericBatchLoadFn<K, V>,
    private val batchScheduleFn: DispatchScheduleFn,
    private val dataLoaderOptions: DataLoaderOptions,
    override val instrumentation: DataLoaderInstrumentation,
) : InternalDispatchStrategy<K, V> {
    private val currentBatch: AtomicReference<InternalDataLoader.Batch<K, V, InternalDataLoader.Batch.Entry<K, V>>?> = AtomicReference(null)

    /**
     * Attempts to add the given [entry] to a batch. If there is no current batch, or the current batch is
     * full or dispatched, a new batch will be created and the entry will be added to that batch.
     *
     * When a new batch is created, we schedule it to be dispatched using the [batchScheduleFn]. This should only be
     * done once per batch.
     *
     * Note, we use a spin lock to add the entry to the batch to ensure that we deal with concurrent threads trying
     * to create/dispatch batches.
     */
    override suspend fun scheduleResult(
        key: K,
        keyContext: Any?,
        result: InternalDataLoader.Batch.BatchResult<V?>,
        onFailedDispatch: (entries: List<K>, throwable: Throwable) -> Unit
    ) {
        while (true) {
            val existingBatch = currentBatch.get()
            val isEntryAdded = existingBatch?.addNewEntry(key, keyContext, result) ?: false

            if (isEntryAdded) {
                break
            }

            // otherwise, create a new batch
            val newBatch = MutexBatch<K, V>(
                instrumentation = instrumentation,
                maxBatchSize = dataLoaderOptions.maxBatchSize
            )

            @Suppress("UNCHECKED_CAST")
            newBatch as InternalDataLoader.Batch<K, V, InternalDataLoader.Batch.Entry<K, V>>

            // if we're not able to set the new batch, we'll continue the loop and try again
            if (this.currentBatch.compareAndSet(existingBatch, newBatch)) {
                // Schedule the batch to be dispatched on the next tick.
                // This should only be ever called once per batch.
                this.batchScheduleFn { dispatchingContext ->
                    newBatch.dispatch(batchLoadFn, dispatchingContext, onFailedDispatch)
                }

                if (newBatch.addNewEntry(key, keyContext, result)) {
                    // successfully added the entry to the batch, so we're done
                    break
                }

                // be extra safe...if we weren't able to add the entry to the batch, it needs to get added,
                // so continue the loop
            }

            yield()
        }
    }

    /**
     * Tracks a batch of entries to be loaded. Leverage a mutex to ensure consistency when adding entries and
     * dispatching the batch.
     */
    internal open class MutexBatch<K, V>(
        private val instrumentation: DataLoaderInstrumentation,
        private val maxBatchSize: Int
    ) : InternalDataLoader.Batch<K, V, MutexBatch.BatchEntry<K, V>> {
        // entries to be loaded
        val entriesToBeLoaded: MutableList<BatchEntry<K, V>> = mutableListOf()

        // we can hold this mutex when updating/checking hasDispatched or adding entries to the batch
        private val mutex = Mutex()

        // whether or not the batch has been dispatched
        private var hasDispatched = false

        // For all keys that are loaded in the same batch, including the ones that are cached
        private val totalKeyCount = AtomicInteger(0)

        private val batchState = instrumentation.createBatchState()

        override suspend fun addNewEntry(
            key: K,
            keyContext: Any?,
            result: InternalDataLoader.Batch.BatchResult<V?>
        ): Boolean =
            if (totalKeyCount.getAndIncrement() < this.maxBatchSize) { // if we're ok on batch size...
                mutex.withLock {
                    // grab a mutex to protect writings
                    if (!hasDispatched) { // check if we've been dispatched
                        val entry = BatchEntry(key, keyContext, result)
                        result.batchState.complete(batchState)
                        entriesToBeLoaded.add(entry) // if not, add the entry
                        instrumentation.onAddBatchEntry(entry.key, entry.keyContext, batchState)
                        true
                    } else {
                        false
                    }
                }
            } else {
                false
            }

        override suspend fun dispatch(
            batchLoadFn: GenericBatchLoadFn<K, V>,
            dispatchingContext: DispatchingContext,
            onFailedDispatch: (keys: List<K>, throwable: Throwable) -> Unit
        ) {
            mutex.withLock {
                if (hasDispatched) {
                    return
                }

                hasDispatched = true
            }
            if (entriesToBeLoaded.isEmpty()) { // don't do anything if we have an empty list
                return
            }
            // actually dispatch...
            try {
                val env = this.getBatchLoaderEnvironment(dispatchingContext)
                // load all the values
                val instrumentedBatchLoadFn = instrumentation.instrumentBatchLoad(batchLoadFn, batchState)
                val values = instrumentedBatchLoadFn.load(entriesToBeLoaded.map { it.key }, env)
                // complete the deferred attached to the batch entries
                entriesToBeLoaded.forEachIndexed { index, entry ->
                    val value = values[index]
                    if (value.error != null) {
                        entry.result.completeExceptionally(value.error)
                    } else {
                        entry.result.complete(value.value)
                    }
                }
            } catch (e: Exception) {
                entriesToBeLoaded.forEach { it.result.completeExceptionally(e) }
                onFailedDispatch(entriesToBeLoaded.map { it.key }, e)
            }
        }

        private fun getBatchLoaderEnvironment(dispatchingContext: DispatchingContext) =
            BatchLoaderEnvironment(
                keyContexts = entriesToBeLoaded.associate { Pair(it.key, it.keyContext) },
                totalKeyCount = entriesToBeLoaded.size,
                dispatchingContext = dispatchingContext
            )

        data class BatchEntry<K, V>(
            override val key: K,
            override val keyContext: Any?,
            override val result: InternalDataLoader.Batch.BatchResult<V?>
        ) : InternalDataLoader.Batch.Entry<K, V>
    }
}

/**
 * Dispatch strategy that does no batching and immediately dispatches the given key
 */
internal class ImmediateDispatchStrategy<K, V>(
    private val batchLoadFn: GenericBatchLoadFn<K, V>,
    override val instrumentation: DataLoaderInstrumentation,
) : InternalDispatchStrategy<K, V> {
    override suspend fun scheduleResult(
        key: K,
        keyContext: Any?,
        result: InternalDataLoader.Batch.BatchResult<V?>,
        onFailedDispatch: (keys: List<K>, throwable: Throwable) -> Unit
    ) {
        try {
            val batchState = instrumentation.createBatchState()
            result.batchState.complete(batchState)

            // Technically speaking... every load call is a new batch entry, so this should be called to maintain consistency with the batch
            // instrumentation behavior
            instrumentation.onAddBatchEntry(key, keyContext, batchState)

            val instrumentedLoad = instrumentation.instrumentBatchLoad(batchLoadFn, batchState)
            val loadResults = instrumentedLoad.load(listOf(key), getBatchLoaderEnv(key, keyContext))

            // We assume only one result is returned since we dispatch immediately and only load 1 key
            val loadResult = loadResults.firstOrNull()

            if (loadResult == null) {
                // If for some reason the load result is empty, complete the result with null; otherwise the load will hang forever
                result.complete(null)
            } else {
                if (loadResult.error != null) {
                    result.completeExceptionally(loadResult.error)
                } else {
                    result.complete(loadResult.value)
                }
            }
        } catch (e: Exception) {
            result.completeExceptionally(e)
            onFailedDispatch(listOf(key), e)
        }
    }

    private fun getBatchLoaderEnv(
        key: K,
        keyContext: Any?
    ) = BatchLoaderEnvironment(
        totalKeyCount = 1,
        keyContexts = mapOf(key to keyContext),
        // Give this just an empty dispatching context
        dispatchingContext = object : DispatchingContext {}
    )
}
