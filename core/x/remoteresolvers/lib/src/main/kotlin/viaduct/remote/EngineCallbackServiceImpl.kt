package viaduct.remote

import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CancellationException
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
        val result = context.resolveSelectionSet(selections, options)
        // A value the codec can't encode (an unresolved reference, a non-JSON-friendly scalar) would
        // otherwise escape the handler as an opaque UNKNOWN; report it as an attributable failure.
        val objectDataJson = try {
            EngineObjectDataSerializer.serialize(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw Status.INTERNAL
                .withDescription("Failed to serialize callback result: ${e.message}")
                .withCause(e)
                .asRuntimeException()
        }
        return QueryResponse.newBuilder()
            .setObjectDataJson(com.google.protobuf.ByteString.copyFrom(objectDataJson))
            .build()
    }

    private fun notFound(
        kind: String,
        handle: String
    ): StatusRuntimeException = Status.NOT_FOUND.withDescription("$kind handle not registered: $handle").asRuntimeException()
}
