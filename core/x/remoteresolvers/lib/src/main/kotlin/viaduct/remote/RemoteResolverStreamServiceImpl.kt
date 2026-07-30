package viaduct.remote

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import viaduct.remote.grpc.BatchResolveFieldResponse
import viaduct.remote.grpc.BatchResolveNodeResponse
import viaduct.remote.grpc.CallbackRequest
import viaduct.remote.grpc.CallbackResponse
import viaduct.remote.grpc.RemoteResolverServiceFieldMessage
import viaduct.remote.grpc.RemoteResolverServiceMessage
import viaduct.remote.grpc.RemoteResolverStreamServiceGrpcKt
import viaduct.remote.grpc.ViaductServiceFieldMessage
import viaduct.remote.grpc.ViaductServiceMessage

/**
 * Bidirectional-streaming implementation of [RemoteResolverStreamServiceGrpcKt]. Additive
 * alongside [RemoteResolverServiceImpl] (the unary implementation) -- new and dormant until wired
 * up by a config-gated cutover.
 *
 * Establishes the stream lifecycle -- reading the first message as the resolve request, wiring a
 * [CallbackDispatcher] to the stream's outbound side, and dispatching inbound callback_response
 * messages to it -- for both RPCs, via [withStreamedCallbacks]. Resolving a batch against a
 * registered executor (node or field) is not implemented yet; each RPC currently answers with an
 * empty resolve_response.
 */
open class RemoteResolverStreamServiceImpl : RemoteResolverStreamServiceGrpcKt.RemoteResolverStreamServiceCoroutineImplBase() {
    override fun resolveNodeBatch(requests: Flow<ViaductServiceMessage>): Flow<RemoteResolverServiceMessage> =
        channelFlow {
            withStreamedCallbacks(
                requests = requests,
                resolveRequestOrNull = { if (it.hasResolveRequest()) it.resolveRequest else null },
                callbackResponseOrNull = { if (it.hasCallbackResponse()) it.callbackResponse else null },
                wrapCallbackRequest = { RemoteResolverServiceMessage.newBuilder().setCallbackRequest(it).build() }
            ) { _, _ ->
                // No registered-executor lookup yet -- always answer empty.
                send(
                    RemoteResolverServiceMessage.newBuilder()
                        .setResolveResponse(BatchResolveNodeResponse.getDefaultInstance())
                        .build()
                )
            }
        }

    override fun resolveFieldBatch(requests: Flow<ViaductServiceFieldMessage>): Flow<RemoteResolverServiceFieldMessage> =
        channelFlow {
            withStreamedCallbacks(
                requests = requests,
                resolveRequestOrNull = { if (it.hasResolveRequest()) it.resolveRequest else null },
                callbackResponseOrNull = { if (it.hasCallbackResponse()) it.callbackResponse else null },
                wrapCallbackRequest = { RemoteResolverServiceFieldMessage.newBuilder().setCallbackRequest(it).build() }
            ) { _, _ ->
                // No registered-executor lookup yet -- always answer empty.
                send(
                    RemoteResolverServiceFieldMessage.newBuilder()
                        .setResolveResponse(BatchResolveFieldResponse.getDefaultInstance())
                        .build()
                )
            }
        }
}

/**
 * Shared stream lifecycle for both RPCs on [RemoteResolverStreamServiceImpl]. Reads the first
 * message as the resolve request, wires a [CallbackDispatcher] to this stream's outbound side,
 * and dispatches subsequent callback_response messages to it until [block] completes, then tears
 * down the reader.
 *
 * A top-level function so it is directly testable with fake request/response types; [block]
 * supplies the resolver-kind-specific resolution logic.
 */
internal suspend fun <TIn, TOut, TReq> ProducerScope<TOut>.withStreamedCallbacks(
    requests: Flow<TIn>,
    resolveRequestOrNull: (TIn) -> TReq?,
    callbackResponseOrNull: (TIn) -> CallbackResponse?,
    wrapCallbackRequest: (CallbackRequest) -> TOut,
    block: suspend (request: TReq, dispatcher: CallbackDispatcher) -> Unit
) {
    val incoming = requests.produceIn(this)
    val first = incoming.receive()
    val request = resolveRequestOrNull(first)
        ?: throw IllegalArgumentException("First message on the stream must be resolve_request")

    val dispatcher = CallbackDispatcher { callbackRequest -> send(wrapCallbackRequest(callbackRequest)) }
    val readerJob = launch {
        try {
            for (message in incoming) {
                callbackResponseOrNull(message)?.let(dispatcher::onCallbackResponse)
            }
        } finally {
            // The loop above is the only thing that completes a pending call's deferred (via
            // onCallbackResponse). If it ends -- stream closed, client disconnected, or this job
            // gets cancelled below -- while a call is still awaiting a response, that response
            // will never arrive; fail it instead of hanging forever.
            dispatcher.failAllPending(IllegalStateException("Callback stream closed before a response arrived"))
        }
    }

    try {
        block(request, dispatcher)
    } finally {
        // Cancelling readerJob alone isn't enough: produceIn's underlying collector coroutine
        // keeps this channelFlow's Job alive until the channel itself is cancelled, regardless of
        // whether the reader loop consuming it has stopped. Without this, a client that keeps its
        // send-side open after the response has been sent leaves the RPC never closing out.
        incoming.cancel()
        readerJob.cancelAndJoin()
    }
}
