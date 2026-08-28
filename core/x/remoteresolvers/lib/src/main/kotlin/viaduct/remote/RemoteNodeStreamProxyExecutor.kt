package viaduct.remote

import io.grpc.ManagedChannel
import java.time.Duration
import java.util.concurrent.TimeUnit
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.remote.api.RemoteResolverContextCaptureInput
import viaduct.remote.api.spi.RemoteResolverContextCapturerProvider
import viaduct.remote.api.spi.RemoteResolverResponseContextApplier
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.BatchResolveNodeResponse
import viaduct.remote.grpc.RemoteResolverStreamServiceGrpcKt
import viaduct.remote.grpc.ViaductServiceMessage

/**
 * RRP-side [RemoteNodeProxyExecutor] that forwards [NodeResolverExecutor.resolve] calls over the
 * bidirectional streaming transport instead of the unary
 * [RemoteResolverServiceImpl]/[UnaryRemoteNodeProxyExecutor] path -- additive alongside it, not a
 * replacement. Re-entrant ctx.query()/ctx.mutation() calls from the resolver running on RRS travel
 * as callback messages on this same stream, driven by [driveClientStream] against the real, local
 * [EngineExecutionContext] passed into `resolve()` -- no callback server, no context_handle, no
 * process-local registry lookup for the callback path.
 *
 * The caller is responsible for the lifecycle of [rrsChannel]; this class does not shut it down.
 */
class RemoteNodeStreamProxyExecutor(
    originalExecutor: NodeResolverExecutor,
    executorId: String,
    rrsChannel: ManagedChannel,
    private val requestDeadline: Duration? = null,
    private val contextCapturerProvider: RemoteResolverContextCapturerProvider =
        RemoteResolverContextCapturerProvider.NO_OP,
    responseContextApplier: RemoteResolverResponseContextApplier =
        RemoteResolverResponseContextApplier.NO_OP,
) : RemoteNodeProxyExecutor(originalExecutor, executorId, responseContextApplier) {
    private val rrsStub = RemoteResolverStreamServiceGrpcKt.RemoteResolverStreamServiceCoroutineStub(rrsChannel)

    override suspend fun callRemote(
        request: BatchResolveNodeRequest,
        context: EngineExecutionContext
    ): BatchResolveNodeResponse {
        val capturedContext = contextCapturerProvider.get().capture(RemoteResolverContextCaptureInput.EMPTY)
        val fullRequest = request.toBuilder()
            .apply { capturedContext?.let { setRemoteContext(it.toWire()) } }
            .build()
        val stub = requestDeadline?.let { rrsStub.withDeadlineAfter(it.toMillis(), TimeUnit.MILLISECONDS) } ?: rrsStub
        return driveClientStream(
            initial = ViaductServiceMessage.newBuilder().setResolveRequest(fullRequest).build(),
            context = context,
            wrapCallbackResponse = { ViaductServiceMessage.newBuilder().setCallbackResponse(it).build() },
            callbackRequestOrNull = { if (it.hasCallbackRequest()) it.callbackRequest else null },
            resolveResponseOrNull = { if (it.hasResolveResponse()) it.resolveResponse else null },
            call = { outgoing -> stub.resolveNodeBatch(outgoing) }
        )
    }
}
