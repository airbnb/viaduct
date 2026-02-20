package viaduct.engine.api.instrumentation.resolver

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element that carries resolver instrumentation through the suspend chain.
 *
 * This allows [viaduct.engine.runtime.SyncEngineObjectDataFactory] to access instrumentation
 * without requiring signature changes to the sync getter lambdas.
 */
class ResolverInstrumentationContext(
    val instrumentation: ViaductResolverInstrumentation,
    val state: ViaductResolverInstrumentation.InstrumentationState
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ResolverInstrumentationContext>
}
