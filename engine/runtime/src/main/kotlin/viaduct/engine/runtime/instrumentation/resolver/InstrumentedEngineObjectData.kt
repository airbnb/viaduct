package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Wraps [viaduct.engine.api.EngineObjectData] to add instrumentation callbacks during field selection fetches.
 *
 * Both [fetch] and [fetchOrNull] notify [resolverInstrumentation] before and after each fetch
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
        val ctx = SafeInstrumentation.execute {
            resolverInstrumentation.beginFetchSelection(parameters = params, state = instrumentationState)
        } ?: return fetchBlock()

        try {
            val result = fetchBlock()
            SafeInstrumentation.execute { ctx.onCompleted(result, null) }
            return result
        } catch (e: Exception) {
            SafeInstrumentation.execute { ctx.onCompleted(null, e) }
            throw e
        }
    }
}
