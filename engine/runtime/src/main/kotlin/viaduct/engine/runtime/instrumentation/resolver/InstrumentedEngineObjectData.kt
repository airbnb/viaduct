package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.SyncFetchFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Wraps [viaduct.engine.api.EngineObjectData] to add instrumentation around field selection fetches.
 *
 * Both [fetch] and [fetchOrNull] wrap the actual fetch operation with [resolverInstrumentation]
 * for observability. Other methods delegate directly to [engineObjectData].
 */
class InstrumentedEngineObjectData(
    val engineObjectData: EngineObjectData,
    val resolverInstrumentation: ViaductResolverInstrumentation,
    val instrumentationState: ViaductResolverInstrumentation.InstrumentationState
) : EngineObjectData {
    override val graphQLObjectType get() = engineObjectData.graphQLObjectType

    override suspend fun fetch(selection: String): Any? = instrumentedFetch(selection) { engineObjectData.fetch(selection) }

    override suspend fun fetchOrNull(selection: String): Any? = instrumentedFetch(selection) { engineObjectData.fetchOrNull(selection) }

    override suspend fun fetchSelections(): Iterable<String> = engineObjectData.fetchSelections()

    private suspend fun instrumentedFetch(
        selection: String,
        fetchBlock: suspend () -> Any?
    ): Any? {
        val params = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(selection = selection)
        return resolverInstrumentation.instrumentFetchSelection(
            FetchFunction { fetchBlock() },
            params,
            instrumentationState
        ).fetch()
    }

    /**
     * Wraps [EngineObjectData.Sync] to add instrumentation around synchronous field selection gets.
     *
     * Both [get] and [getOrNull] wrap the actual get operation with [resolverInstrumentation]
     * for observability. Other methods delegate directly to [engineObjectData].
     */
    class Sync(
        val engineObjectData: EngineObjectData.Sync,
        val resolverInstrumentation: ViaductResolverInstrumentation,
        val instrumentationState: ViaductResolverInstrumentation.InstrumentationState
    ) : EngineObjectData.Sync {
        override val graphQLObjectType get() = engineObjectData.graphQLObjectType

        override fun get(selection: String): Any? = instrumentedGet(selection) { engineObjectData.get(selection) }

        override fun getOrNull(selection: String): Any? = instrumentedGet(selection) { engineObjectData.getOrNull(selection) }

        override fun getSelections(): Iterable<String> = engineObjectData.getSelections()

        override suspend fun fetch(selection: String): Any? = get(selection)

        override suspend fun fetchOrNull(selection: String): Any? = getOrNull(selection)

        override suspend fun fetchSelections(): Iterable<String> = getSelections()

        private fun instrumentedGet(
            selection: String,
            getBlock: () -> Any?
        ): Any? {
            val params = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(selection = selection)
            return resolverInstrumentation.instrumentSyncFetchSelection(
                SyncFetchFunction { getBlock() },
                params,
                instrumentationState
            ).fetch()
        }
    }
}
