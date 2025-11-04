package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Test instrumentation that throws exceptions on method calls.
 * Used to verify that instrumentation failures don't break core operations.
 */
class ThrowingResolverInstrumentation(
    private val exceptionMessage: String = "Instrumentation failed",
    private val throwOnCreateState: Boolean = false,
    private val throwOnInstrumentExecute: Boolean = false,
    private val throwOnInstrumentFetch: Boolean = false
) : ViaductResolverInstrumentation {
    override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
        if (throwOnCreateState) {
            throw RuntimeException(exceptionMessage)
        }
        return RecordingResolverInstrumentation.RecordingInstrumentationState()
    }

    override fun <T> instrumentResolverExecution(
        resolver: ResolverFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): ResolverFunction<T> {
        if (throwOnInstrumentExecute) {
            throw RuntimeException(exceptionMessage)
        }
        return resolver
    }

    override fun <T> instrumentFetchSelection(
        fetchFn: FetchFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): FetchFunction<T> {
        if (throwOnInstrumentFetch) {
            throw RuntimeException(exceptionMessage)
        }
        return fetchFn
    }
}
