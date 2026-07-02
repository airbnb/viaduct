package viaduct.remote

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.remote.grpc.BatchResolveFieldRequest
import viaduct.remote.grpc.BatchResolveFieldResponse
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.BatchResolveNodeResponse
import viaduct.remote.grpc.ErrorInfo
import viaduct.remote.grpc.RemoteResolverServiceGrpcKt
import viaduct.remote.grpc.ResolvedField
import viaduct.remote.grpc.ResolvedNode
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SchemaRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * gRPC service that executes node resolvers on behalf of a [RemoteNodeProxyExecutor].
 *
 * Looks up the executor by handle, wraps the context so re-entrant queries route back
 * to the caller over gRPC, invokes the resolver, and serializes the result. Network
 * mode (handle absent from the local registry) falls back to a stub context fed by
 * [SchemaRegistry] and an empty selection set.
 */
open class RemoteResolverServiceImpl : RemoteResolverServiceGrpcKt.RemoteResolverServiceCoroutineImplBase() {
    private val log = LoggerFactory.getLogger(RemoteResolverServiceImpl::class.java)

    // Persistent per-endpoint channel — a fresh ManagedChannel per request would spin up a
    // Netty thread pool each time.
    private val callbackChannelCache = ConcurrentHashMap<String, ManagedChannel>()

    override suspend fun batchResolveNode(request: BatchResolveNodeRequest): BatchResolveNodeResponse {
        log.debug("Received batchResolveNode request (executorId={}, contextHandle={})", request.executorId, request.contextHandle)

        val executor = NodeExecutorRegistry.get(request.executorId)
            ?: throw notFound("executor", request.executorId)
        val remoteContext = buildRemoteContext(request.contextHandle, request.callbackEndpoint)

        // IN_PROCESS shares the JVM registry; NETWORK mode lacks it, so the resolver runs
        // with an empty selection set and the proxy side projects requested fields client-
        // side. Wire-level selection-set serialization would unlock RRS-side pruning but
        // isn't required for correctness.
        val selectors = request.selectorsList.map { protoSelector ->
            val selections = SelectionsRegistry.get(protoSelector.selectionsHandle)
                ?: EmptyEngineSelectionSet(executor.typeName)
            NodeResolverExecutor.Selector(id = protoSelector.id, selections = selections)
        }

        val results = try {
            executor.resolve(selectors, remoteContext)
        } catch (e: Exception) {
            log.error("Resolver execution failed for type '{}': {}", executor.typeName, e.message, e)
            return buildErrorResponse(selectors, e)
        }

        // serialize is suspend — can't use map {} here.
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

    override suspend fun batchResolveField(request: BatchResolveFieldRequest): BatchResolveFieldResponse {
        log.debug("Received batchResolveField request (executorId={}, contextHandle={})", request.executorId, request.contextHandle)

        val executor = FieldExecutorRegistry.get(request.executorId)
            ?: throw notFound("field executor", request.executorId)
        val remoteContext = buildRemoteContext(request.contextHandle, request.callbackEndpoint)

        // Object/query values arrive serialized; deserialize them against the resolver's real schema
        // types (not placeholders) so the field context's type-name and field checks pass. The
        // resolver id is the field coordinate, "Type.field".
        val parentTypeName = request.executorId.substringBefore(".")
        val schema = remoteContext.fullSchema.schema
        val objectType = schema.getObjectType(parentTypeName) ?: throw notFound("object type", parentTypeName)
        val queryType = schema.queryType
        // Selector keys correlate the response (a field Selector has no natural id). deserialize is
        // suspend, so build with a loop.
        val keyedSelectors = mutableListOf<Pair<String, FieldResolverExecutor.Selector>>()
        for (proto in request.selectorsList) {
            val objectValue = EngineObjectDataSerializer.deserialize(proto.objectValueJson.toByteArray(), objectType)
            val queryValue = EngineObjectDataSerializer.deserialize(proto.queryValueJson.toByteArray(), queryType)
            val arguments = FieldValueSerializer.deserializeArguments(proto.argumentsJson.toByteArray())
            val selections = proto.selectionsHandle.takeIf { it.isNotEmpty() }?.let { SelectionsRegistry.get(it) }
            keyedSelectors.add(
                proto.selectorKey to FieldResolverExecutor.Selector(
                    arguments = arguments,
                    selections = selections,
                    syncObjectValueGetter = { objectValue },
                    syncQueryValueGetter = { queryValue }
                )
            )
        }

        val results = try {
            executor.batchResolve(keyedSelectors.map { it.second }, remoteContext)
        } catch (e: Exception) {
            log.error("Field resolver execution failed for '{}': {}", request.executorId, e.message, e)
            return buildFieldErrorResponse(keyedSelectors.map { it.first }, e)
        }

        // Isolate a non-serializable success value (e.g. an object-typed field) to that selector's
        // error instead of failing the whole batch.
        val protoResults = keyedSelectors.map { (key, selector) ->
            val result = results[selector]
            when {
                result == null -> fieldError(key, IllegalStateException("Resolver returned no result for selector '$key'"))
                result.isSuccess ->
                    try {
                        ResolvedField.newBuilder()
                            .setSelectorKey(key)
                            .setValueJson(com.google.protobuf.ByteString.copyFrom(FieldValueSerializer.serializeValue(result.getOrNull())))
                            .build()
                    } catch (e: Exception) {
                        fieldError(key, e)
                    }
                else -> fieldError(key, result.exceptionOrNull()!!)
            }
        }

        log.debug("Returning {} field result(s) for executor '{}'", protoResults.size, request.executorId)
        return BatchResolveFieldResponse.newBuilder()
            .addAllResults(protoResults)
            .build()
    }

    /** Drains and clears the callback channel cache. Call during RRS shutdown. */
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

    // Builds the context for an incoming resolve. Re-entrant queries route back to the caller over
    // the cached callback channel; when the caller's context isn't in this JVM (network mode), the
    // locally registered schema is used instead.
    private fun buildRemoteContext(
        contextHandle: String,
        callbackEndpoint: String
    ): RemoteEngineExecutionContext {
        val originalContext = ContextRegistry.get(contextHandle)
        val callbackChannel = callbackChannelCache.computeIfAbsent(callbackEndpoint) { createCallbackChannel(it) }
        return RemoteEngineExecutionContext(
            delegate = originalContext,
            callbackChannel = callbackChannel,
            contextHandle = contextHandle,
            localSchema = if (originalContext == null) SchemaRegistry.get() else null
        )
    }

    private fun fieldError(
        selectorKey: String,
        error: Throwable
    ): ResolvedField =
        ResolvedField.newBuilder()
            .setSelectorKey(selectorKey)
            .setError(
                ErrorInfo.newBuilder()
                    .setMessage(error.message ?: "Field resolver execution failed")
                    .setErrorType(error::class.java.name)
                    .build()
            )
            .build()

    private fun buildFieldErrorResponse(
        selectorKeys: List<String>,
        error: Exception
    ): BatchResolveFieldResponse {
        val results = selectorKeys.map { fieldError(it, error) }
        return BatchResolveFieldResponse.newBuilder().addAllResults(results).build()
    }

    // "host:port" → network channel; anything else → in-process channel name.
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
