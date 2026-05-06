package viaduct.engine.api.instrumentation.resolver

import graphql.execution.ResultPath
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.Coordinate
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

    /**
     * Whether this instrumentation performs any meaningful work in [instrumentFetchSelection]
     * for a given request.
     *
     * When true, [viaduct.engine.runtime.instrumentation.resolver.InstrumentedEngineObjectData]
     * wraps nested [viaduct.engine.api.EngineObjectData] values returned by fetch operations.
     * When false, wrapping is skipped, avoiding unnecessary allocations on the hot path.
     * [instrumentReadSelection] is always invoked on field reads and is not gated by this flag.
     * Resolver instrumentation via [instrumentResolverExecution] is unaffected by this flag.
     *
     */
    fun shouldInstrumentFetchSelections(state: InstrumentationState?): Boolean = false

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
        val resolverMetadata: ResolverMetadata,
        val fieldCoordinate: Coordinate? = null,
        val syncValueComputation: Boolean = false,
        val executionPath: ResultPath? = null,
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
        val selection: String,
        val parentTypeName: String? = null,
        val resultPath: ResultPath? = null
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
     * Wraps synchronous selection reading with instrumentation.
     * Called when reading pre-materialized field data from [viaduct.engine.api.EngineObjectData.Sync].
     * @param fetchFn The sync fetch function to instrument
     * @param parameters Parameters for the read operation
     * @param state The instrumentation state
     * @return The instrumented sync fetch function
     */
    fun <T> instrumentReadSelection(
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
