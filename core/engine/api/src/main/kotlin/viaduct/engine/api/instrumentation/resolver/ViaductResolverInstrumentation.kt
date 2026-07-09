package viaduct.engine.api.instrumentation.resolver

import graphql.execution.ResultPath
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
        val resolverMetadata: ResolverMetadata,
        val fieldCoordinate: Coordinate? = null,
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
     * Handle for an in-flight selection materialization observation.
     */
    fun interface FetchSelectionInstrumentation {
        fun finish(cause: Throwable?)

        companion object {
            val NOOP = FetchSelectionInstrumentation {}
        }
    }

    /**
     * Starts selection materialization instrumentation.
     *
     * The caller is responsible for invoking [FetchSelectionInstrumentation.finish] when the
     * selection's slot values have completed. This observes materialization without wrapping the
     * awaited work or changing resolver scheduling.
     */
    fun beginFetchSelection(
        parameters: InstrumentFetchSelectionParameters,
        state: InstrumentationState?,
    ): FetchSelectionInstrumentation = FetchSelectionInstrumentation.NOOP

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
}
