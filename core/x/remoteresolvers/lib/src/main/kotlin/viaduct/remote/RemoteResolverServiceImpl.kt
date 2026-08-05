package viaduct.remote

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.Status
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.remote.api.RemoteResolverContextException
import viaduct.remote.api.spi.RemoteResolverContextApplier
import viaduct.remote.grpc.BatchResolveFieldRequest
import viaduct.remote.grpc.BatchResolveFieldResponse
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.BatchResolveNodeResponse
import viaduct.remote.grpc.ErrorInfo
import viaduct.remote.grpc.RemoteResolverServiceGrpcKt
import viaduct.remote.grpc.ResolvedField
import viaduct.remote.grpc.SerializedSelectionSet
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.FieldExecutorRegistry
import viaduct.remote.registry.NodeExecutorRegistry
import viaduct.remote.registry.SchemaRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * gRPC service that executes node resolvers on behalf of a [RemoteNodeProxyExecutor].
 *
 * Looks up the executor by handle, wraps the context so re-entrant queries route back
 * to the caller over gRPC, invokes the resolver, and serializes the result. When a
 * handle is absent from the local registry, the service falls back to a stub context
 * fed by [SchemaRegistry] and an empty selection set.
 */
open class RemoteResolverServiceImpl(
    private val contextApplier: RemoteResolverContextApplier = RemoteResolverContextApplier.NO_OP,
) : RemoteResolverServiceGrpcKt.RemoteResolverServiceCoroutineImplBase() {
    private val log = LoggerFactory.getLogger(RemoteResolverServiceImpl::class.java)

    // Persistent per-endpoint channel — a fresh ManagedChannel per request would spin up a
    // Netty thread pool each time.
    private val callbackChannelCache = ConcurrentHashMap<String, ManagedChannel>()

    final override suspend fun batchResolveNode(request: BatchResolveNodeRequest): BatchResolveNodeResponse =
        runWithRemoteContext(request.hasRemoteContext(), request.remoteContext) {
            batchResolveNodeInternal(request)
        }

    private suspend fun batchResolveNodeInternal(request: BatchResolveNodeRequest): BatchResolveNodeResponse {
        log.debug("Received batchResolveNode request (executorId={}, contextHandle={})", request.executorId, request.contextHandle)

        // Fail fast on a missing executor before building the remote context: buildRemoteContext
        // may dial a callback channel (createCallbackChannel), which throws on a malformed
        // endpoint -- that shouldn't mask a NOT_FOUND for an executor that was never registered.
        if (NodeExecutorRegistry.get(request.executorId) == null) throw notFound("executor", request.executorId)
        val remoteContext = buildRemoteContext(request.contextHandle, request.callbackEndpoint)
        val results = resolveNodeExecutorBatch(request.executorId, request.selectorsList, remoteContext)

        log.debug("Returning {} result(s) for executor '{}'", results.size, request.executorId)
        return BatchResolveNodeResponse.newBuilder()
            .addAllResults(results)
            .build()
    }

    final override suspend fun batchResolveField(request: BatchResolveFieldRequest): BatchResolveFieldResponse =
        runWithRemoteContext(request.hasRemoteContext(), request.remoteContext) {
            batchResolveFieldInternal(request)
        }

    private suspend fun batchResolveFieldInternal(request: BatchResolveFieldRequest): BatchResolveFieldResponse {
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
        // A per-selector deserialization failure (malformed payload, unknown type, unparseable
        // selection set) is isolated to that selector's error — matching the resolver-execution and
        // value-serialization paths below — so one bad selector can't abort the whole batch.
        val preFailed = mutableListOf<ResolvedField>()
        for (proto in request.selectorsList) {
            try {
                val objectValue = EngineObjectDataSerializer.deserialize(proto.objectValueJson.toByteArray(), objectType)
                val queryValue = EngineObjectDataSerializer.deserialize(proto.queryValueJson.toByteArray(), queryType)
                val arguments = FieldValueSerializer.deserializeArguments(proto.argumentsJson.toByteArray())
                // Prefer a resolvable registry handle, which preserves object identity; otherwise
                // reconstruct the selection set shipped by the caller.
                val selections = proto.selectionsHandle.takeIf { it.isNotEmpty() }?.let { SelectionsRegistry.get(it) }
                    ?: if (proto.hasSelections()) reconstructSelections(proto.selections, remoteContext) else null
                keyedSelectors.add(
                    proto.selectorKey to FieldResolverExecutor.Selector(
                        arguments = arguments,
                        selections = selections,
                        syncObjectValueGetter = { objectValue },
                        syncQueryValueGetter = { queryValue }
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Failed to deserialize field selector '{}' for '{}': {}", proto.selectorKey, request.executorId, e.message, e)
                preFailed.add(fieldError(proto.selectorKey, e))
            }
        }

        // Every selector pre-failed deserialization — return their errors without invoking the resolver.
        // An unbatched built-in resolver asserts `require(selectors.size == 1)`, so calling it with an
        // empty batch would throw and misattribute the deserialization failures to the resolver.
        if (keyedSelectors.isEmpty()) {
            return BatchResolveFieldResponse.newBuilder().addAllResults(preFailed).build()
        }

        val results = try {
            executor.batchResolve(keyedSelectors.map { it.second }, remoteContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Field resolver execution failed for '{}': {}", request.executorId, e.message, e)
            return BatchResolveFieldResponse.newBuilder()
                .addAllResults(keyedSelectors.map { fieldError(it.first, e) })
                .addAllResults(preFailed)
                .build()
        }

        // Isolate a non-serializable success value to that selector's error rather than fail the batch.
        // (serializeValue is suspend, but `map` is inline, so no manual accumulator loop is needed.)
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
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fieldError(key, e)
                    }
                else -> fieldError(key, result.exceptionOrNull()!!)
            }
        } + preFailed

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

    // Rebuilds a field's sub-selection set from its serialized form against the remote's own schema
    // (via the context's selection-set factory). Used when the per-JVM selections handle isn't
    // resolvable here. A blank document means an empty selection set.
    private fun reconstructSelections(
        proto: SerializedSelectionSet,
        context: RemoteEngineExecutionContext
    ): EngineSelectionSet =
        if (proto.document.isBlank()) {
            EmptyEngineSelectionSet(proto.type)
        } else {
            context.engineSelectionSetFactory.engineSelectionSet(
                proto.type,
                proto.document,
                FieldValueSerializer.deserializeArguments(proto.variablesJson.toByteArray())
            )
        }

    // Builds the context for an incoming resolve. Re-entrant queries route back to the caller over
    // the cached callback channel; when the caller's context isn't registered locally, the locally
    // registered schema is used instead.
    private fun buildRemoteContext(
        contextHandle: String,
        callbackEndpoint: String
    ): RemoteEngineExecutionContext {
        val originalContext = ContextRegistry.get(contextHandle)
        val callbackChannel = callbackChannelCache.computeIfAbsent(callbackEndpoint) { createCallbackChannel(it) }
        return UnaryRemoteEngineExecutionContext(
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

    private suspend fun <T> runWithRemoteContext(
        hasRemoteContext: Boolean,
        wireContext: viaduct.remote.grpc.EncodedRemoteContext,
        block: suspend () -> T,
    ): T {
        var blockStarted = false
        try {
            return contextApplier.apply(wireContext.takeIf { hasRemoteContext }?.fromWire()) {
                blockStarted = true
                block()
            }
        } catch (e: RemoteResolverContextException) {
            if (blockStarted) throw e
            throw Status.INVALID_ARGUMENT
                .withDescription(e.message)
                .withCause(e)
                .asRuntimeException()
        }
    }

    /** Creates the network channel used for re-entrant callbacks. Tests may override the transport. */
    protected open fun createCallbackChannel(endpoint: String): ManagedChannel {
        val separator = endpoint.lastIndexOf(':')
        require(separator > 0 && separator < endpoint.lastIndex) {
            "Callback endpoint must use host:port format: '$endpoint'"
        }
        val host = endpoint.substring(0, separator)
        val port = endpoint.substring(separator + 1).toIntOrNull()
        require(port != null) { "Callback endpoint must use host:port format: '$endpoint'" }

        log.debug("Creating callback channel to {}:{}", host, port)
        return ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .build()
    }
}
