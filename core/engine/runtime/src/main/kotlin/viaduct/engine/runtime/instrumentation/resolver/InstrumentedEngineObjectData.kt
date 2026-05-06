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
 *
 * Nested [EngineObjectData] values returned by fetch operations are also wrapped with
 * [InstrumentedEngineObjectData] so that instrumentation propagates through the full selection
 * set hierarchy.
 */
class InstrumentedEngineObjectData(
    val engineObjectData: EngineObjectData,
    val resolverInstrumentation: ViaductResolverInstrumentation,
    val instrumentationState: ViaductResolverInstrumentation.InstrumentationState
) : EngineObjectData {
    init {
        require(engineObjectData !is InstrumentedEngineObjectData) {
            "EngineObjectData may only be instrumented once"
        }
    }

    override val type get() = engineObjectData.type

    override suspend fun fetch(selection: String): Any? = instrumentedFetch(selection) { engineObjectData.fetch(selection) }

    override suspend fun fetchOrNull(selection: String): Any? = instrumentedFetch(selection) { engineObjectData.fetchOrNull(selection) }

    override suspend fun fetchSelections(): Iterable<String> = engineObjectData.fetchSelections()

    private suspend fun instrumentedFetch(
        selection: String,
        fetchBlock: suspend () -> Any?
    ): Any? {
        val params = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(
            selection = selection,
            parentTypeName = engineObjectData.type.name
        )
        val value = resolverInstrumentation.instrumentFetchSelection(
            FetchFunction { fetchBlock() },
            params,
            instrumentationState
        ).fetch()
        return wrapNestedEngineObjectData(value)
    }

    private fun wrapNestedEngineObjectData(value: Any?): Any? =
        when (value) {
            is InstrumentedEngineObjectData -> value
            is EngineObjectData -> InstrumentedEngineObjectData(value, resolverInstrumentation, instrumentationState)
            is List<*> -> copyOnWrapList(value, ::wrapNestedEngineObjectData)
            else -> value
        }

    /**
     * Wraps [EngineObjectData.Sync] to add instrumentation around synchronous field selection gets.
     *
     * Both [get] and [getOrNull] wrap the actual get operation with [resolverInstrumentation]
     * for observability. Other methods delegate directly to [engineObjectData].
     *
     * Nested [EngineObjectData.Sync] values returned by get operations are also wrapped with
     * [InstrumentedEngineObjectData.Sync] so that instrumentation propagates through the full
     * selection set hierarchy.
     */
    class Sync(
        val engineObjectData: EngineObjectData.Sync,
        val resolverInstrumentation: ViaductResolverInstrumentation,
        val instrumentationState: ViaductResolverInstrumentation.InstrumentationState
    ) : EngineObjectData.Sync {
        init {
            require(engineObjectData !is Sync) {
                "EngineObjectData.Sync may only be instrumented once"
            }
        }

        override val type get() = engineObjectData.type

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
            val params = ViaductResolverInstrumentation.InstrumentFetchSelectionParameters(
                selection = selection,
                parentTypeName = engineObjectData.type.name
            )
            val value = resolverInstrumentation.instrumentReadSelection(
                SyncFetchFunction { getBlock() },
                params,
                instrumentationState
            ).fetch()
            return wrapNestedSyncEngineObjectData(value)
        }

        private fun wrapNestedSyncEngineObjectData(value: Any?): Any? =
            when (value) {
                is Sync -> value
                is EngineObjectData.Sync -> Sync(value, resolverInstrumentation, instrumentationState)
                is List<*> -> copyOnWrapList(value, ::wrapNestedSyncEngineObjectData)
                else -> value
            }
    }
}

/**
 * Traverses [list] and applies [wrap] to each element, returning the original list unchanged if no
 * element required wrapping. This avoids allocating a new list for the common case where all
 * elements are scalars (strings, ints, etc.) rather than [viaduct.engine.api.EngineObjectData].
 *
 * Uses copy-on-first-wrap: once a wrapped element differs from the original, a new list is
 * allocated, pre-populated with the already-seen elements via [subList], and then filled
 * with subsequent (possibly wrapped) elements.
 */
private fun copyOnWrapList(
    list: List<*>,
    wrap: (Any?) -> Any?
): List<*> {
    var result: ArrayList<Any?>? = null
    for (i in list.indices) {
        val item = list[i]
        val wrapped = wrap(item)
        if (result != null) {
            result.add(wrapped)
        } else if (wrapped !== item) {
            // First element that needs wrapping: lazily allocate the result list with the full
            // capacity, bulk-copy the elements we already verified didn't need wrapping (indices
            // 0..i-1), then add the wrapped version of element i. Subsequent iterations will
            // always hit the `result != null` branch and just append.
            result = ArrayList<Any?>(list.size).also { it.addAll(list.subList(0, i)) }
            result.add(wrapped)
        }
    }
    return result ?: list
}
