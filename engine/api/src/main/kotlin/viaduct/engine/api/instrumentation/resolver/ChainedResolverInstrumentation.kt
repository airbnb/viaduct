package viaduct.engine.api.instrumentation.resolver

import kotlin.collections.forEach

/**
 * Composite instrumentation that chains multiple [ViaductResolverInstrumentation] implementations.
 *
 * Invokes all instrumentations in the list sequentially for each lifecycle event. Each instrumentation
 * maintains its own state, and completion callbacks are called in order for all instrumentations.
 *
 * Example:
 * ```
 * ChainedResolverInstrumentation(
 *     listOf(metricsInstrumentation, tracingInstrumentation)
 * )
 * ```
 */
class ChainedResolverInstrumentation(
    val instrumentations: List<ViaductResolverInstrumentation>
) : ViaductResolverInstrumentation {
    /**
     * Composite state that holds individual states for each instrumentation in the chain.
     */
    data class ChainedInstrumentationState(
        val states: Map<ViaductResolverInstrumentation, ViaductResolverInstrumentation.InstrumentationState>
    ) : ViaductResolverInstrumentation.InstrumentationState {
        fun getState(instrumentation: ViaductResolverInstrumentation) = states[instrumentation]
    }

    override fun createInstrumentationState(parameters: ViaductResolverInstrumentation.CreateInstrumentationStateParameters): ViaductResolverInstrumentation.InstrumentationState {
        val states = instrumentations.associate { it to it.createInstrumentationState(parameters) }
        return ChainedInstrumentationState(states.toMap())
    }

    override fun beginExecuteResolver(
        parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?
    ): ViaductResolverInstrumentation.OnCompleted {
        state as ChainedInstrumentationState
        return chainBegin(state) {
                instrumentation, instrState ->
            instrumentation.beginExecuteResolver(parameters, instrState)
        }
    }

    override fun beginFetchSelection(
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?
    ): ViaductResolverInstrumentation.OnCompleted {
        state as ChainedInstrumentationState
        return chainBegin(state) {
                instrumentation, instrState ->
            instrumentation.beginFetchSelection(parameters, instrState)
        }
    }

    private fun chainBegin(
        state: ChainedInstrumentationState,
        beginOperation: (ViaductResolverInstrumentation, ViaductResolverInstrumentation.InstrumentationState?) -> ViaductResolverInstrumentation.OnCompleted
    ): ViaductResolverInstrumentation.OnCompleted {
        val completedCtx = instrumentations.map { beginOperation(it, state.getState(it)) }
        return ViaductResolverInstrumentation.OnCompleted { result, error ->
            completedCtx.forEach { it.onCompleted(result, error) }
        }
    }
}
