package viaduct.remote

import io.grpc.ManagedChannel
import java.time.Duration
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.remote.grpc.BatchResolveNodeRequest
import viaduct.remote.grpc.RemoteResolverServiceGrpcKt
import viaduct.remote.grpc.Selector as ProtoSelector
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * RRP-side proxy that forwards [NodeResolverExecutor.resolve] calls over gRPC to a
 * [RemoteResolverService] and returns the deserialized results to the engine.
 *
 * The caller is responsible for the lifecycle of [rrsChannel]; this class does
 * not shut it down. [callbackEndpoint] is the address the remote service dials
 * for re-entrant queries — either "host:port" (network) or an in-process channel name.
 */
class RemoteNodeProxyExecutor(
    private val originalExecutor: NodeResolverExecutor,
    private val executorId: String,
    rrsChannel: ManagedChannel,
    private val callbackEndpoint: String,
    private val requestDeadline: Duration? = null
) : NodeResolverExecutor {
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

    private val log = LoggerFactory.getLogger(RemoteNodeProxyExecutor::class.java)
    private val rrsStub = RemoteResolverServiceGrpcKt.RemoteResolverServiceCoroutineStub(rrsChannel)

    // Synthesized once because type identity is not propagated over the wire; the
    // downstream builder only needs a name.
    private val remoteResultType = graphql.schema.GraphQLObjectType.newObject()
        .name(originalExecutor.typeName)
        .build()

    override val typeName: String
        get() = originalExecutor.typeName

    override val isBatching: Boolean
        get() = originalExecutor.isBatching

    override val isSelective: Boolean
        get() = originalExecutor.isSelective

    override val metadata: ResolverMetadata
        get() = originalExecutor.metadata

    override suspend fun resolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        log.debug("Proxying {} resolver(s) for type '{}' to remote execution", selectors.size, typeName)

        val contextHandle = ContextRegistry.register(context)
        val protoSelectors = selectors.map { selector ->
            val selectionsHandle = SelectionsRegistry.register(selector.selections)
            ProtoSelector.newBuilder()
                .setId(selector.id)
                .setSelectionsHandle(selectionsHandle)
                .build()
        }
        val selectionsHandles = protoSelectors.map { it.selectionsHandle }

        val request = BatchResolveNodeRequest.newBuilder()
            .setExecutorId(executorId)
            .addAllSelectors(protoSelectors)
            .setContextHandle(contextHandle)
            .setCallbackEndpoint(callbackEndpoint)
            .build()

        // Handles are valid only for the duration of this RPC — unregister in finally
        // so a failure doesn't leak the context or selection-set references.
        val stub = requestDeadline?.let { rrsStub.withDeadlineAfter(it.toMillis(), TimeUnit.MILLISECONDS) } ?: rrsStub
        val response = try {
            stub.batchResolveNode(request)
        } finally {
            ContextRegistry.unregister(contextHandle)
            selectionsHandles.forEach { SelectionsRegistry.unregister(it) }
        }
        log.debug("Received {} result(s) for executor '{}'", response.resultsCount, executorId)

        return response.resultsList.associate { resolvedNode ->
            val selector = selectors.find { it.id == resolvedNode.selectorId }
                ?: error("Response contained unknown selector ID: ${resolvedNode.selectorId}")
            val result = when {
                resolvedNode.hasDataJson() -> Result.success(
                    EngineObjectDataSerializer.deserialize(resolvedNode.dataJson.toByteArray(), remoteResultType)
                )
                resolvedNode.hasError() -> Result.failure(
                    RemoteResolverException(message = resolvedNode.error.message, errorType = resolvedNode.error.errorType)
                )
                else -> error("Response for selector ${resolvedNode.selectorId} has neither data nor error")
            }
            selector to result
        }
    }
}

class RemoteResolverException(
    message: String,
    val errorType: String
) : RuntimeException("Remote resolver error ($errorType): $message")
