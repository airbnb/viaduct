package viaduct.remote

import io.grpc.Status
import io.grpc.StatusRuntimeException
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.remote.grpc.EngineCallbackServiceGrpcKt
import viaduct.remote.grpc.QueryRequest
import viaduct.remote.grpc.QueryResponse
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * gRPC callback service exposed by the engine side (RRP).
 *
 * Receives re-entrant query/mutation requests from resolvers executing in a
 * [RemoteResolverService], looks up the originating [EngineExecutionContext] and
 * selection set by handle, and drives execution back through the engine.
 */
class EngineCallbackServiceImpl : EngineCallbackServiceGrpcKt.EngineCallbackServiceCoroutineImplBase() {
    override suspend fun executeQuery(request: QueryRequest): QueryResponse = execute(request, ResolveSelectionSetOptions.DEFAULT)

    override suspend fun executeMutation(request: QueryRequest): QueryResponse = execute(request, ResolveSelectionSetOptions.MUTATION)

    private suspend fun execute(
        request: QueryRequest,
        options: ResolveSelectionSetOptions
    ): QueryResponse {
        val context = ContextRegistry.get(request.contextHandle)
            ?: throw notFound("context", request.contextHandle)
        val selections = SelectionsRegistry.get(request.selectionsHandle)
            ?: throw notFound("selections", request.selectionsHandle)
        val result = context.resolveSelectionSetSync(selections, options)
        return QueryResponse.newBuilder()
            .setObjectDataJson(com.google.protobuf.ByteString.copyFrom(EngineObjectDataSerializer.serialize(result)))
            .build()
    }

    private fun notFound(
        kind: String,
        handle: String
    ): StatusRuntimeException = Status.NOT_FOUND.withDescription("$kind handle not registered: $handle").asRuntimeException()
}
