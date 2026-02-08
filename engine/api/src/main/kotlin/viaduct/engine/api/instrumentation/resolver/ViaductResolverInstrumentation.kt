package viaduct.engine.api.instrumentation.resolver

import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.ResolverMetadata

/**
 * A function interface for resolver execution.
 */
fun interface ResolverFunction<T> {
    suspend fun resolve(): T
}

/**
 * A function interface for field selection fetching.
 */
fun interface FetchFunction<T> {
    suspend fun fetch(): T
}

/**
 * A function interface for synchronous field selection fetching.
 */
fun interface SyncFetchFunction<T> {
    fun fetch(): T
}

/**
 * A function interface for access checker execution.
 */
fun interface CheckerFunction<T> {
    suspend fun check(): T
}

/**
 * Instrumentation interface for observing Viaduct Modern resolver execution lifecycle.
 *
 * Implementations can track metrics, tracing, logging, or other observability concerns
 * for resolver execution. Use [viaduct.engine.runtime.instrumentation.resolver.ChainedResolverInstrumentation] to compose multiple instrumentations.
 */
interface ViaductResolverInstrumentation {
    /**
     * Opaque state object that can be passed between instrumentation lifecycle methods.
     */
    interface InstrumentationState

    companion object {
        /** Default no-op instrumentation state */
        val DEFAULT_INSTRUMENTATION_STATE = object : InstrumentationState {}

        /** Default no-op instrumentation implementation */
        val DEFAULT = object : ViaductResolverInstrumentation {}
    }

    data class CreateInstrumentationStateParameters(
        val placeholder: Boolean = false
    )

    /**
     * Create instrumentation state for a GraphQL request.
     * Called once per request to initialize any state needed across resolver invocations.
     */
    fun createInstrumentationState(parameters: CreateInstrumentationStateParameters): InstrumentationState = DEFAULT_INSTRUMENTATION_STATE

    data class InstrumentExecuteResolverParameters(
        val resolverMetadata: ResolverMetadata
    )

    /**
     * Wraps resolver execution with instrumentation.
     * @param resolver The resolver function to instrument
     * @param parameters Parameters for the resolver execution
     * @param state The instrumentation state
     * @return The instrumented resolver function
     */
    fun <T> instrumentResolverExecution(
        resolver: ResolverFunction<T>,
        parameters: InstrumentExecuteResolverParameters,
        state: InstrumentationState?,
    ): ResolverFunction<T> = resolver

    data class InstrumentFetchSelectionParameters(
        val selection: String
    )

    /**
     * Wraps selection fetching with instrumentation.
     * @param fetchFn The fetch function to instrument
     * @param parameters Parameters for the fetch operation
     * @param state The instrumentation state
     * @return The instrumented fetch function
     */
    fun <T> instrumentFetchSelection(
        fetchFn: FetchFunction<T>,
        parameters: InstrumentFetchSelectionParameters,
        state: InstrumentationState?,
    ): FetchFunction<T> = fetchFn

    /**
     * Wraps synchronous selection fetching with instrumentation.
     * @param fetchFn The sync fetch function to instrument
     * @param parameters Parameters for the fetch operation
     * @param state The instrumentation state
     * @return The instrumented sync fetch function
     */
    fun <T> instrumentSyncFetchSelection(
        fetchFn: SyncFetchFunction<T>,
        parameters: InstrumentFetchSelectionParameters,
        state: InstrumentationState?,
    ): SyncFetchFunction<T> = fetchFn

    data class InstrumentExecuteCheckerParameters(
        val checkerMetadata: CheckerMetadata
    )

    /**
     * Wraps access checker execution with instrumentation.
     * @param checker The checker function to instrument
     * @param parameters Parameters for the checker execution
     * @param state The instrumentation state
     * @return The instrumented checker function
     */
    fun <T> instrumentAccessChecker(
        checker: CheckerFunction<T>,
        parameters: InstrumentExecuteCheckerParameters,
        state: InstrumentationState?,
    ): CheckerFunction<T> = checker
}
