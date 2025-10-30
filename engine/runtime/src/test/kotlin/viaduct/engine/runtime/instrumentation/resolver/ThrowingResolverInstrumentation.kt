package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Test instrumentation that throws exceptions on method calls.
 * Used to verify that instrumentation failures don't break core operations.
 */
class ThrowingResolverInstrumentation(
    private val exceptionMessage: String = "Instrumentation failed",
    private val throwOnCreateState: Boolean = false,
    private val throwOnBeginExecute: Boolean = false,
    private val throwOnBeginFetch: Boolean = false,
    private val throwOnCompleted: Boolean = false
) : ViaductResolverInstrumentation {
    class ThrowingOnCompleted(private val exceptionMessage: String) : ViaductResolverInstrumentation.OnCompleted {
        override fun onCompleted(
            result: Any?,
            error: Throwable?
        ) {
            throw RuntimeException(exceptionMessage)
        }
    }

    override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
        if (throwOnCreateState) {
            throw RuntimeException(exceptionMessage)
        }
        return RecordingResolverInstrumentation.RecordingInstrumentationState()
    }

    override fun beginExecuteResolver(
        parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?
    ): ViaductResolverInstrumentation.OnCompleted {
        if (throwOnBeginExecute) {
            throw RuntimeException(exceptionMessage)
        }
        return if (throwOnCompleted) {
            ThrowingOnCompleted(exceptionMessage)
        } else {
            ViaductResolverInstrumentation.NOOP_ON_COMPLETED
        }
    }

    override fun beginFetchSelection(
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?
    ): ViaductResolverInstrumentation.OnCompleted {
        if (throwOnBeginFetch) {
            throw RuntimeException(exceptionMessage)
        }
        return if (throwOnCompleted) {
            ThrowingOnCompleted(exceptionMessage)
        } else {
            ViaductResolverInstrumentation.NOOP_ON_COMPLETED
        }
    }
}
