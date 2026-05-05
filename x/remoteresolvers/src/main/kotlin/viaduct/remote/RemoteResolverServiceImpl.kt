package viaduct.remote

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.BatchResolveNodeResponse
import viaduct.remote.grpc.ErrorInfo
import viaduct.remote.grpc.RemoteResolverServiceGrpcKt
import viaduct.remote.grpc.ResolvedNode
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.ExecutorRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * gRPC service that executes node resolvers on behalf of a [RemoteNodeProxyExecutor].
 *
 * Looks up the [NodeResolverExecutor] and originating [EngineExecutionContext] by
 * handle, wraps the context so re-entrant queries route back to the caller over gRPC,
 * invokes the resolver, and serializes the results.
 */
open class RemoteResolverServiceImpl : RemoteResolverServiceGrpcKt.RemoteResolverServiceCoroutineImplBase() {
    private val log = LoggerFactory.getLogger(RemoteResolverServiceImpl::class.java)

    // One persistent callback channel per endpoint. Creating a ManagedChannel per request
    // would instantiate a full Netty thread pool each time — unsustainable at load.
    private val callbackChannelCache = ConcurrentHashMap<String, ManagedChannel>()

    override suspend fun batchResolveNode(request: BatchResolveNodeRequest): BatchResolveNodeResponse {
        log.debug("Received batchResolveNode request (executorId={}, contextHandle={})", request.executorId, request.contextHandle)

        val executor = ExecutorRegistry.get(request.executorId)
            ?: throw notFound("executor", request.executorId)
        val originalContext = ContextRegistry.get(request.contextHandle)
            ?: throw notFound("context", request.contextHandle)

        val callbackChannel = callbackChannelCache.computeIfAbsent(request.callbackEndpoint) { ep ->
            createCallbackChannel(ep)
        }
        val remoteContext = RemoteEngineExecutionContext(
            delegate = originalContext,
            callbackChannel = callbackChannel,
            contextHandle = request.contextHandle
        )

        val selectors = request.selectorsList.map { protoSelector ->
            val selections = SelectionsRegistry.get(protoSelector.selectionsHandle)
                ?: throw notFound("selections", protoSelector.selectionsHandle)
            NodeResolverExecutor.Selector(id = protoSelector.id, selections = selections)
        }

        val results = try {
            executor.resolve(selectors, remoteContext)
        } catch (e: Exception) {
            log.error("Resolver execution failed for type '{}': {}", executor.typeName, e.message, e)
            return buildErrorResponse(selectors, e)
        }

        // For-loop (not map {}) because serialize is suspend.
        val protoResults = mutableListOf<ResolvedNode>()
        for ((selector, result) in results) {
            val node = if (result.isSuccess) {
                val serialized = EngineObjectDataSerializer.serialize(result.getOrThrow())
                ResolvedNode.newBuilder()
                    .setSelectorId(selector.id)
                    .setDataJson(com.google.protobuf.ByteString.copyFrom(serialized))
                    .build()
            } else {
                val error = result.exceptionOrNull()!!
                ResolvedNode.newBuilder()
                    .setSelectorId(selector.id)
                    .setError(
                        ErrorInfo.newBuilder()
                            .setMessage(error.message ?: "Unknown error")
                            .setErrorType(error::class.java.name)
                            .build()
                    )
                    .build()
            }
            protoResults.add(node)
        }

        log.debug("Returning {} result(s) for executor '{}'", protoResults.size, request.executorId)
        return BatchResolveNodeResponse.newBuilder()
            .addAllResults(protoResults)
            .build()
    }

    /** Shuts down cached callback channels. Intended to be called during RRS shutdown. */
    fun shutdownChannels() {
        callbackChannelCache.values.forEach { channel ->
            channel.shutdown()
            try {
                if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                    channel.shutdownNow()
                }
            } catch (e: InterruptedException) {
                channel.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
        callbackChannelCache.clear()
    }

    private fun notFound(
        kind: String,
        handle: String
    ): StatusRuntimeException = Status.NOT_FOUND.withDescription("$kind handle not registered: $handle").asRuntimeException()

    private fun buildErrorResponse(
        selectors: List<NodeResolverExecutor.Selector>,
        error: Exception
    ): BatchResolveNodeResponse {
        val errorInfo = ErrorInfo.newBuilder()
            .setMessage(error.message ?: "Resolver execution failed")
            .setErrorType(error::class.java.name)
            .build()
        val protoResults = selectors.map { selector ->
            ResolvedNode.newBuilder()
                .setSelectorId(selector.id)
                .setError(errorInfo)
                .build()
        }
        return BatchResolveNodeResponse.newBuilder()
            .addAllResults(protoResults)
            .build()
    }

    /**
     * Creates a channel matching the endpoint format: a "host:port" string maps to a
     * network channel; anything else is treated as an in-process channel name.
     */
    private fun createCallbackChannel(endpoint: String): ManagedChannel {
        val networkParts = endpoint.split(":")
        return if (networkParts.size == 2 && networkParts[1].toIntOrNull() != null) {
            val host = networkParts[0]
            val port = networkParts[1].toInt()
            log.debug("Creating network callback channel to {}:{}", host, port)
            ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()
        } else {
            log.debug("Creating in-process callback channel: {}", endpoint)
            InProcessChannelBuilder.forName(endpoint)
                .directExecutor()
                .build()
        }
    }
}
