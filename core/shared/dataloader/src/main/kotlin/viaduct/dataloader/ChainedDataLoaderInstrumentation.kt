package viaduct.dataloader

/**
 * Allow for multiple instrumentations to be chained together. This instrumentation will take care of making sure the correct state objects are passed
 * to the correct instrumentations for each instrumentation method.
 */
class ChainedDataLoaderInstrumentation(
    private val instrumentations: List<DataLoaderInstrumentation>
) : DataLoaderInstrumentation {
    private data class BatchState(
        val instrumentationsAndStates: List<Pair<DataLoaderInstrumentation, DataLoaderInstrumentation.BatchState>>
    ) : DataLoaderInstrumentation.BatchState

    override fun createBatchState(): DataLoaderInstrumentation.BatchState = BatchState(instrumentations.map { Pair(it, it.createBatchState()) })

    override fun <K> beginLoad(
        key: K,
        keyContext: Any?,
        batchState: DataLoaderInstrumentation.BatchState,
    ): DataLoaderInstrumentation.OnCompleteLoad {
        batchState as BatchState
        val delegateOnCompletes = batchState.instrumentationsAndStates.map { (instrumentation, batchState) ->
            instrumentation.beginLoad(key, keyContext, batchState)
        }

        return object : DataLoaderInstrumentation.OnCompleteLoad {
            override fun <V> onComplete(
                result: V?,
                exception: Throwable?,
                batchState: DataLoaderInstrumentation.BatchState
            ) {
                batchState as BatchState

                batchState.instrumentationsAndStates.forEachIndexed { index, (_, batchState) ->
                    val correspondingOnCompleteLoad = delegateOnCompletes[index]
                    correspondingOnCompleteLoad.onComplete(result, exception, batchState)
                }
            }
        }
    }

    override fun <K> onAddBatchEntry(
        key: K,
        keyContext: Any?,
        batchState: DataLoaderInstrumentation.BatchState
    ) {
        batchState as BatchState
        batchState.instrumentationsAndStates.forEach { (inst, instBatchState) ->
            inst.onAddBatchEntry(key, keyContext, instBatchState)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <K, V> instrumentBatchLoad(
        loadFn: GenericBatchLoadFn<K, V>,
        batchState: DataLoaderInstrumentation.BatchState
    ): GenericBatchLoadFn<K, V> {
        batchState as BatchState
        return batchState.instrumentationsAndStates.fold(loadFn) { accFn, (inst, instBatchState) ->
            inst.instrumentBatchLoad(accFn, instBatchState)
        }
    }
}
