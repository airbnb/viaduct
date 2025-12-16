package viaduct.dataloader

/**
 * Allows instrumentation of DataLoaders.
 *
 * Exists for the lifecycle of the Dataloader, but enables instrumentation of individual load calls, as well as the underlying batch load function.
 */
interface DataLoaderInstrumentation {
    interface BatchState

    companion object {
        private val DEFAULT_BATCH_STATE = object : BatchState {}
        private val DEFAULT_ON_COMPLETE_LOAD =
            object : OnCompleteLoad {
                override fun <V> onComplete(
                    result: V?,
                    exception: Throwable?,
                    batchState: BatchState
                ) {
                }
            }

        val DEFAULT = object : DataLoaderInstrumentation {}
    }

    interface OnCompleteLoad {
        fun <V> onComplete(
            result: V?,
            exception: Throwable?,
            batchState: BatchState
        )
    }

    /**
     * Creates initial state for the instrumentation. A new state is created for each batch.
     * Defaults to an empty state object
     */
    fun createBatchState(): BatchState = DEFAULT_BATCH_STATE

    /**
     * Called for each load call into the dataloader. Important distinction is that this is not instrumenting the batch dispatch function, but instead
     * the individual load calls that are made to the dataloader for individual values.
     *
     * @return Callback that will be called when the load is complete
     */
    fun <K> beginLoad(
        key: K,
        keyContext: Any?,
        batchState: BatchState,
    ): OnCompleteLoad = DEFAULT_ON_COMPLETE_LOAD

    /**
     * Called when an entry is added to the batch. This is called once for each entry added to the batch.
     *
     * Use case is if we only want to keep track on things that result in changes to the batch load. (i.e. for field context, we don't care about all
     * the fields that result in dataloader entries being read, but instead only care about the fields that can be actually attributed to downstream
     * loads. So we take the first field in a type backed by a dataloader.
     */
    fun <K> onAddBatchEntry(
        key: K,
        keyContext: Any?,
        batchState: BatchState
    ) {}

    /**
     * Allows instrumenting the batch load function provided to the DataLoader. This can be used to wrap the batch load
     * function with additional functionality, such as logging, metrics, etc.
     */
    suspend fun <K, V> instrumentBatchLoad(
        loadFn: GenericBatchLoadFn<K, V>,
        batchState: BatchState
    ): GenericBatchLoadFn<K, V> = loadFn
}
