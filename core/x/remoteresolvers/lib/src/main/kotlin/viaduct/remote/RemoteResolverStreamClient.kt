package viaduct.remote

import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.remote.grpc.CallbackRequest
import viaduct.remote.grpc.CallbackResponse
import viaduct.remote.grpc.ErrorInfo

/**
 * Drives one client-opened bidirectional stream: sends [initial] as the first outbound message,
 * answers each incoming callback_request against the real, local [context] -- the whole point of
 * the streaming transport, no callback server and no process-local registry lookup -- and returns
 * the terminal resolve_response.
 *
 * Resolver-kind-agnostic (node or field): the caller supplies the concrete proto message types
 * via [wrapCallbackResponse]/[callbackRequestOrNull]/[resolveResponseOrNull]/[call].
 */
internal suspend fun <TOut, TIn, TResp> driveClientStream(
    initial: TOut,
    context: EngineExecutionContext,
    wrapCallbackResponse: (CallbackResponse) -> TOut,
    callbackRequestOrNull: (TIn) -> CallbackRequest?,
    resolveResponseOrNull: (TIn) -> TResp?,
    call: (Flow<TOut>) -> Flow<TIn>
): TResp {
    val outgoing = Channel<TOut>(Channel.UNLIMITED)
    outgoing.send(initial)

    var response: TResp? = null
    try {
        coroutineScope {
            call(outgoing.consumeAsFlow()).collect { message ->
                callbackRequestOrNull(message)?.let { callbackRequest ->
                    // Each callback is handled in its own coroutine so concurrent callbacks (multiple
                    // selectors calling ctx.query() at overlapping times) don't block each other or
                    // this collect loop.
                    launch {
                        outgoing.send(wrapCallbackResponse(handleCallbackRequest(callbackRequest, context)))
                    }
                    return@collect
                }
                resolveResponseOrNull(message)?.let {
                    response = it
                    outgoing.close()
                }
            }
        }
    } finally {
        // Guarantees the outbound half of the stream is always closed -- not just on the happy
        // path above -- so a thrown exception (transport failure, a failed callback coroutine
        // propagating through coroutineScope) or the incoming flow ending without ever producing a
        // resolve_response can't leak the underlying send side of the stream. A no-op if already
        // closed.
        outgoing.close()
    }
    return response ?: error("Stream completed without a resolve_response")
}

/**
 * Answers one re-entrant ctx.query()/ctx.mutation() callback against the real, local
 * [EngineExecutionContext]: deserializes the selection set's content (not a handle) and resolves
 * it directly, with no process-local registry lookup on the callback path. A blank document means
 * an empty selection set (mirrors [RemoteResolverServiceImpl]'s reconstructSelections) --
 * SelectionsParser.parse throws on a blank document, so this can't just fall through to
 * engineSelectionSet.
 */
private suspend fun handleCallbackRequest(
    request: CallbackRequest,
    context: EngineExecutionContext
): CallbackResponse =
    try {
        val selections = if (request.selections.document.isBlank()) {
            EmptyEngineSelectionSet(request.selections.type)
        } else {
            context.engineSelectionSetFactory.engineSelectionSet(
                request.selections.type,
                request.selections.document,
                FieldValueSerializer.deserializeArguments(request.selections.variablesJson.toByteArray())
            )
        }
        val options = if (request.isMutation) ResolveSelectionSetOptions.MUTATION else ResolveSelectionSetOptions.DEFAULT
        val result = context.resolveSelectionSet(selections, options)
        CallbackResponse.newBuilder()
            .setCorrelationId(request.correlationId)
            .setObjectDataJson(ByteString.copyFrom(EngineObjectDataSerializer.serialize(result)))
            .build()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Callback request failed: type='{}' document='{}'", request.selections.type, request.selections.document, e)
        CallbackResponse.newBuilder()
            .setCorrelationId(request.correlationId)
            .setError(
                ErrorInfo.newBuilder()
                    .setMessage(e.message ?: "Callback failed")
                    .setErrorType(e::class.java.name)
                    .build()
            )
            .build()
    }

private val log = LoggerFactory.getLogger("viaduct.remote.RemoteResolverStreamClient")
