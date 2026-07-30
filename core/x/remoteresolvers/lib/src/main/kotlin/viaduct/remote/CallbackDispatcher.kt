package viaduct.remote

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import viaduct.remote.grpc.CallbackRequest
import viaduct.remote.grpc.CallbackResponse
import viaduct.remote.grpc.SerializedSelectionSet

/**
 * Dispatches re-entrant ctx.query()/ctx.mutation() calls over a single bidirectional stream and
 * correlates their responses back to the awaiting caller.
 *
 * Multiple calls may be in flight concurrently on the same stream. Each is keyed by a unique
 * correlation ID and completed when [onCallbackResponse] is invoked with a matching response.
 */
internal class CallbackDispatcher(
    private val sendRequest: suspend (CallbackRequest) -> Unit
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<CallbackResponse>>()
    private val nextId = AtomicLong()

    /** The number of calls currently awaiting a response. Exposed for tests. */
    internal val pendingCount: Int get() = pending.size

    // Set once the reader loop has ended (see failAllPending) -- a call registered afterward has
    // no reader left to ever complete or fail it via onCallbackResponse, so it must fail fast here
    // instead of awaiting a response that will never arrive.
    private val closedCause = AtomicReference<Throwable?>(null)

    /** Sends a callback request and suspends until its correlated response arrives. */
    suspend fun call(
        selections: SerializedSelectionSet,
        resolverId: String,
        isMutation: Boolean
    ): CallbackResponse {
        closedCause.get()?.let { throw it }
        val id = "cb-${nextId.getAndIncrement()}"
        val deferred = CompletableDeferred<CallbackResponse>()
        pending[id] = deferred // synchronous, strictly before the send below -- no race
        // Re-check after inserting: closes the window against a failAllPending that ran between
        // the check above and this insert (its pending-snapshot would've missed this entry).
        closedCause.get()?.let {
            pending.remove(id)
            throw it
        }
        try {
            sendRequest(
                CallbackRequest.newBuilder()
                    .setCorrelationId(id)
                    .setSelections(selections)
                    .setResolverId(resolverId)
                    .setIsMutation(isMutation)
                    .build()
            )
            return deferred.await()
        } catch (e: Exception) {
            // Covers both a failed send and cancellation of the caller while awaiting -- either
            // way no response will ever arrive (or is no longer wanted), so this entry must not
            // linger in pending for the rest of the dispatcher's lifetime.
            pending.remove(id)
            throw e
        }
    }

    /** Routes an incoming CallbackResponse to its awaiting caller. Call from the stream's reader loop. */
    fun onCallbackResponse(response: CallbackResponse) {
        pending.remove(response.correlationId)?.complete(response)
    }

    /**
     * Fails every still-pending call with [cause], and every future [call] from here on -- call
     * this once the stream's reader loop ends (normally or not). Otherwise a call awaiting a
     * response that will never arrive (the client disconnected, the stream errored) hangs forever,
     * since nothing but [onCallbackResponse] would ever complete its deferred -- including one
     * registered after this sweep, since the reader loop that would've driven it is already gone.
     */
    fun failAllPending(cause: Throwable) {
        closedCause.compareAndSet(null, cause)
        pending.keys.toList().forEach { id -> pending.remove(id)?.completeExceptionally(cause) }
    }
}
