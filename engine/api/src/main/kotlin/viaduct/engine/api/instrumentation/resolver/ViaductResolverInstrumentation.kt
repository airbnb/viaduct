package viaduct.engine.api.instrumentation.resolver

import viaduct.engine.api.ResolverMetadata

/**
 * Instrumentation interface for observing Viaduct Modern resolver execution lifecycle.
 *
 * Implementations can track metrics, tracing, logging, or other observability concerns
 * for resolver execution. Use [ChainedResolverInstrumentation] to compose multiple instrumentations.
 */
interface ViaductResolverInstrumentation {
    /**
     * Opaque state object that can be passed between instrumentation lifecycle methods.
     */
    interface InstrumentationState

    companion object {
        /** Default no-op instrumentation state */
        val DEFAULT_INSTRUMENTATION_STATE = object : InstrumentationState {}

        /** Default no-op completion callback */
        val NOOP_ON_COMPLETED = OnCompleted { _, _ -> }

        /** Default no-op instrumentation implementation */
        val DEFAULT = object : ViaductResolverInstrumentation {}
    }

    /**
     * Functional interface for callbacks invoked when an instrumented operation completes.
     * This allows for SAM (Single Abstract Method) conversion from lambdas.
     */
    fun interface OnCompleted {
        /**
         * Called when the operation completes.
         * @param result The result value if successful, null otherwise
         * @param error The error if operation failed, null otherwise
         */
        fun onCompleted(
            result: Any?,
            error: Throwable?,
        )
    }

    data class CreateInstrumentationStateParameters(
        val resolverMetadata: ResolverMetadata,
    )

    /**
     * Create instrumentation state for a GraphQL request.
     * Called once per request to initialize any state needed across resolver invocations.
     */
    fun createInstrumentationState(parameters: CreateInstrumentationStateParameters): InstrumentationState = DEFAULT_INSTRUMENTATION_STATE

    data class InstrumentExecuteResolverParameters(
        val placeholder: Boolean = false
    )

    /**
     * Called before a resolver executes.
     * @return OnCompleted callback that will be invoked when resolver execution completes
     */
    fun beginExecuteResolver(
        parameters: InstrumentExecuteResolverParameters,
        state: InstrumentationState?,
    ): OnCompleted = NOOP_ON_COMPLETED

    data class InstrumentFetchSelectionParameters(
        val selection: String
    )

    /**
     * Called before fetching a selection within a resolver.
     * @return OnCompleted callback that will be invoked when resolver execution completes
     */
    fun beginFetchSelection(
        parameters: InstrumentFetchSelectionParameters,
        state: InstrumentationState?,
    ): OnCompleted = NOOP_ON_COMPLETED
}
