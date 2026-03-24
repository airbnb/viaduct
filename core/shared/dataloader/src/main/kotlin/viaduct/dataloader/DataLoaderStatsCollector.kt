package viaduct.dataloader

interface DataLoaderStatsCollector {
    interface InstrumentationDebug {
        fun toMap(): Map<String, Any?>
    }

    fun logTotalKeyCount(
        loaderInfo: DataLoader.DataLoaderInfo,
        keyCount: Int
    )

    fun logDefaultLoad(
        loaderInfo: DataLoader.DataLoaderInfo,
        keys: Collection<Any>,
        dispatchingContext: DispatchingContext
    )

    fun logActualLoads(
        loaderInfo: DataLoader.DataLoaderInfo,
        keys: Collection<Any>,
        dispatchingContext: DispatchingContext
    )

    fun logLoadScheduleLatency(
        loaderInfo: DataLoader.DataLoaderInfo,
        latencyNs: Long,
        dispatchingContext: DispatchingContext,
    )

    fun logLoadTotalLatency(
        loaderInfo: DataLoader.DataLoaderInfo,
        latencyNs: Long,
        delayNs: Long,
        dispatchingContext: DispatchingContext,
    )

    /**
     * StatsCollector has hooks to allow for custom logging from dataloader instrumentation at the finish of a request.
     */
    fun registerInstrumentationDebug(
        debugName: String,
        loaderInfo: DataLoader.DataLoaderInfo,
        instrumentationDebugFn: () -> InstrumentationDebug?
    )
}
