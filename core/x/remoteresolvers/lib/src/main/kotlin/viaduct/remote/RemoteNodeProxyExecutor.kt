package viaduct.remote

import io.grpc.ManagedChannel
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.remote.api.RemoteResolverContextCaptureInput
import viaduct.remote.api.spi.RemoteResolverContextCapturerProvider
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.BatchResolveNodeResponse
import viaduct.remote.grpc.RemoteResolverServiceGrpcKt
import viaduct.remote.grpc.Selector as ProtoSelector
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * [NodeResolverExecutor] proxy shared by both remote-resolver transports: unary gRPC
 * ([UnaryRemoteNodeProxyExecutor]) and bidirectional streaming ([RemoteNodeStreamProxyExecutor]).
 * Builds proto selectors (registering selection handles), maps the response back to selector
 * results, and unregisters handles regardless of outcome. Only how the base request (executor id
 * + selectors only) actually reaches the remote service ([callRemote]) differs by transport, so
 * it's the one abstract member; a subclass needing transport-specific fields (e.g. the unary
 * path's context_handle/callback_endpoint) adds them via `.toBuilder()` before invoking its RPC.
 */
abstract class RemoteNodeProxyExecutor(
    private val originalExecutor: NodeResolverExecutor,
    protected val executorId: String,
) : NodeResolverExecutor {
    private val log = LoggerFactory.getLogger(RemoteNodeProxyExecutor::class.java)

    init {
        // Selective resolvers can batch multiple selectors with the same node id but different
        // selection sets. The wire protocol currently keys responses by node id, so selectors
        // are indistinguishable once they come back — fail fast here rather than silently
        // returning the wrong result for one of them.
        require(!originalExecutor.isSelective) {
            "Remote execution of selective node resolvers is not yet supported " +
                "(type='${originalExecutor.typeName}'). Track progress in the remote-resolver README."
        }
    }

    override val typeName: String
        get() = originalExecutor.typeName

    override val isBatching: Boolean
        get() = originalExecutor.isBatching

    override val isSelective: Boolean
        get() = originalExecutor.isSelective

    override val metadata: ResolverMetadata
        get() = originalExecutor.metadata

    final override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        val protoSelectors = selectors.map { selector ->
            ProtoSelector.newBuilder()
                .setId(selector.id)
                .setSelectionsHandle(SelectionsRegistry.register(selector.selections))
                .build()
        }
        val request = BatchResolveNodeRequest.newBuilder()
            .setExecutorId(executorId)
            .addAllSelectors(protoSelectors)
            .build()

        log.debug("Proxying {} resolver(s) for type '{}' to remote execution", protoSelectors.size, typeName)
        val response = try {
            callRemote(request, context)
        } finally {
            // Handles are valid only for the duration of this RPC — unregister regardless of
            // outcome so a failure doesn't leak the selection-set references.
            protoSelectors.forEach { SelectionsRegistry.unregister(it.selectionsHandle) }
        }
        log.debug("Received {} result(s) for executor '{}'", response.resultsCount, executorId)

        // deserialize asserts the wire's type against this node's own type.
        val schema = context.fullSchema.schema

        val selectorsById = selectors.associateBy { it.id }
        return response.resultsList.associate { resolvedNode ->
            val selector = selectorsById[resolvedNode.selectorId]
                ?: error("Response contained unknown selector ID: ${resolvedNode.selectorId}")
            val result = when {
                resolvedNode.hasDataJson() ->
                    isolatedRemoteFailure("Failed to deserialize remote node data") {
                        EngineObjectDataSerializer.deserialize(resolvedNode.dataJson.toByteArray(), schema, typeName)
                    }.onFailure {
                        log.warn("Failed to decode node '{}' for executor '{}'", resolvedNode.selectorId, executorId, it)
                    }
                resolvedNode.hasError() -> Result.failure(
                    RemoteResolverException(message = resolvedNode.error.message, errorType = resolvedNode.error.errorType)
                )
                else -> error("Response for selector ${resolvedNode.selectorId} has neither data nor error")
            }
            selector to result
        }
    }

    /**
     * Sends the base [request] (executor id + selectors only) to the remote service and returns
     * its response.
     */
    protected abstract suspend fun callRemote(
        request: BatchResolveNodeRequest,
        context: EngineExecutionContext
    ): BatchResolveNodeResponse
}

/**
 * The unary-gRPC [RemoteNodeProxyExecutor]: forwards [NodeResolverExecutor.resolve] calls over a
 * single unary RPC to a [RemoteResolverService], with [callbackEndpoint] as the address the
 * remote service dials back for re-entrant queries.
 *
 * The caller is responsible for the lifecycle of [rrsChannel]; this class does not shut it down.
 */
class UnaryRemoteNodeProxyExecutor(
    originalExecutor: NodeResolverExecutor,
    executorId: String,
    rrsChannel: ManagedChannel,
    private val callbackEndpoint: String,
    private val requestDeadline: Duration? = null,
    private val contextCapturerProvider: RemoteResolverContextCapturerProvider =
        RemoteResolverContextCapturerProvider.NO_OP,
) : RemoteNodeProxyExecutor(originalExecutor, executorId) {
    private val rrsStub = RemoteResolverServiceGrpcKt.RemoteResolverServiceCoroutineStub(rrsChannel)

    override suspend fun callRemote(
        request: BatchResolveNodeRequest,
        context: EngineExecutionContext
    ): BatchResolveNodeResponse {
        val capturedContext = contextCapturerProvider.get().capture(RemoteResolverContextCaptureInput.EMPTY)
        val contextHandle = ContextRegistry.register(context, currentCoroutineContext())
        val fullRequest = request.toBuilder()
            .setContextHandle(contextHandle)
            .setCallbackEndpoint(callbackEndpoint)
            .apply { capturedContext?.let { setRemoteContext(it.toWire()) } }
            .build()

        // Handle is valid only for the duration of this RPC — unregister in finally so a
        // failure doesn't leak the context reference.
        val stub = requestDeadline?.let { rrsStub.withDeadlineAfter(it.toMillis(), TimeUnit.MILLISECONDS) } ?: rrsStub
        return try {
            stub.batchResolveNode(fullRequest)
        } finally {
            ContextRegistry.unregister(contextHandle)
        }
    }
}

class RemoteResolverException(
    message: String,
    val errorType: String,
    cause: Throwable? = null
) : RuntimeException("Remote resolver error ($errorType): $message", cause)

/**
 * Runs [block], converting a failure into a [RemoteResolverException] result so one bad item in a batch
 * fails only itself. Cancellation is rethrown, not converted.
 */
internal inline fun <T> isolatedRemoteFailure(
    fallbackMessage: String,
    block: () -> T
): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(RemoteResolverException(e.message ?: fallbackMessage, e::class.java.name, e))
    }
