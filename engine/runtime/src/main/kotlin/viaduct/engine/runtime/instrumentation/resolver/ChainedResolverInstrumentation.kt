package viaduct.engine.runtime.instrumentation.resolver

import viaduct.engine.api.instrumentation.resolver.CheckerFunction
import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.ResolverFunction
import viaduct.engine.api.instrumentation.resolver.SyncFetchFunction
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation

/**
 * Composite instrumentation that chains multiple [viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation] implementations.
 *
 * Invokes all instrumentations in the list sequentially for each lifecycle event. Each instrumentation
 * maintains its own state and wraps the execution of the next instrumentation in the chain.
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

    override fun <T> instrumentResolverExecution(
        resolver: ResolverFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentExecuteResolverParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): ResolverFunction<T> {
        state as ChainedInstrumentationState
        return instrumentations.foldRight(resolver) { instrumentation, next ->
            val instrState = state.getState(instrumentation)
            instrumentation.instrumentResolverExecution(next, parameters, instrState)
        }
    }

    override fun <T> instrumentFetchSelection(
        fetchFn: FetchFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): FetchFunction<T> {
        state as ChainedInstrumentationState
        return instrumentations.foldRight(fetchFn) { instrumentation, next ->
            val instrState = state.getState(instrumentation)
            instrumentation.instrumentFetchSelection(next, parameters, instrState)
        }
    }

    override fun <T> instrumentSyncFetchSelection(
        fetchFn: SyncFetchFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?,
    ): SyncFetchFunction<T> {
        state as ChainedInstrumentationState
        return instrumentations.foldRight(fetchFn) { instrumentation, next ->
            val instrState = state.getState(instrumentation)
            instrumentation.instrumentSyncFetchSelection(next, parameters, instrState)
        }
    }

    override fun <T> instrumentAccessChecker(
        checker: CheckerFunction<T>,
        parameters: ViaductResolverInstrumentation.InstrumentExecuteCheckerParameters,
        state: ViaductResolverInstrumentation.InstrumentationState?
    ): CheckerFunction<T> {
        state as ChainedInstrumentationState
        return instrumentations.foldRight(checker) { instrumentation, next ->
            val instrState = state.getState(instrumentation)
            instrumentation.instrumentAccessChecker(next, parameters, instrState)
        }
    }
}
