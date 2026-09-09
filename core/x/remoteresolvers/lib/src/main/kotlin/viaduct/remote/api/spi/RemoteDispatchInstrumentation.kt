package viaduct.remote.api.spi

import viaduct.engine.api.ResolverMetadata

/**
 * Per-dispatch handle returned by [RemoteDispatchInstrumentation.beginRemoteDispatch].
 *
 * Callers report each checkpoint as it completes; [onCompleted] is always called last, exactly
 * once, regardless of outcome. A checkpoint that never applies to a given call site (e.g.
 * deserialization when the RPC itself failed) is simply never invoked -- callers do not need to
 * report absence.
 */
interface RemoteDispatchInstrumentationContext {
    /** Aggregate result of one remote dispatch, reported via [onCompleted]. */
    enum class RemoteDispatchOutcome {
        /** The dispatch completed and every selector resolved successfully. */
        SUCCESS,

        /** The RPC itself failed (e.g. connectivity, deadline) before a response was received. */
        TRANSPORT_ERROR,

        /** This side's own request/response serialization or deserialization failed -- a local codec bug. */
        CODEC_ERROR,

        /** The response was received, but the remote resolver itself failed for one or more selectors. */
        APPLICATION_ERROR,
    }

    /** Metadata about the RPC response, reported via [onResponseReceived]. */
    data class RemoteDispatchResponse(
        val resolverExecutionLatencyNs: Long? = null,
    )

    /** Selector serialization finished, successfully or not. */
    fun onSerializationCompleted(error: Throwable? = null)

    /** The RPC returned a response (or failed to). */
    fun onResponseReceived(
        response: RemoteDispatchResponse?,
        error: Throwable? = null
    )

    /** Response deserialization finished, successfully or not. */
    fun onDeserializationCompleted(error: Throwable? = null)

    /** The dispatch is complete. Always called exactly once, in a finally-equivalent position. */
    fun onCompleted(
        outcome: RemoteDispatchOutcome,
        cause: Throwable?
    )

    companion object {
        val NOOP: RemoteDispatchInstrumentationContext =
            object : RemoteDispatchInstrumentationContext {
                override fun onSerializationCompleted(error: Throwable?) {}

                override fun onResponseReceived(
                    response: RemoteDispatchResponse?,
                    error: Throwable?
                ) {}

                override fun onDeserializationCompleted(error: Throwable?) {}

                override fun onCompleted(
                    outcome: RemoteDispatchOutcome,
                    cause: Throwable?
                ) {}
            }
    }
}

/**
 * Experimental SPI for observing remote-resolver dispatch latency and outcome, independent of
 * [viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation] -- remote dispatch
 * is specific to this transport, not a general resolver-execution concern.
 */
interface RemoteDispatchInstrumentation {
    data class BeginRemoteDispatchParameters(val resolverMetadata: ResolverMetadata)

    /** Starts instrumentation for one remote dispatch (one batch RPC). */
    fun beginRemoteDispatch(parameters: BeginRemoteDispatchParameters): RemoteDispatchInstrumentationContext

    companion object {
        val NO_OP: RemoteDispatchInstrumentation =
            object : RemoteDispatchInstrumentation {
                override fun beginRemoteDispatch(parameters: BeginRemoteDispatchParameters): RemoteDispatchInstrumentationContext = RemoteDispatchInstrumentationContext.NOOP
            }
    }
}
