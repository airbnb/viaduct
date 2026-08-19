package viaduct.remote

import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import viaduct.deferred.ThreadLocalCoroutineContextManager
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.remote.grpc.EngineCallbackServiceGrpcKt
import viaduct.remote.grpc.QueryRequest
import viaduct.remote.grpc.QueryResponse
import viaduct.remote.grpc.SerializedSelectionSet
import viaduct.remote.registry.ContextRegistry
import viaduct.remote.registry.SelectionsRegistry

/**
 * gRPC callback service exposed by the engine side (RRP).
 *
 * Executes remote query and mutation callbacks against a registered [EngineExecutionContext].
 * Serialized selections take precedence over same-JVM selection handles.
 */
class EngineCallbackServiceImpl : EngineCallbackServiceGrpcKt.EngineCallbackServiceCoroutineImplBase() {
    override suspend fun executeQuery(request: QueryRequest): QueryResponse = execute(request, ResolveSelectionSetOptions.DEFAULT)

    override suspend fun executeMutation(request: QueryRequest): QueryResponse = execute(request, ResolveSelectionSetOptions.MUTATION)

    private suspend fun execute(
        request: QueryRequest,
        options: ResolveSelectionSetOptions
    ): QueryResponse {
        val registration = ContextRegistry.getRegistration(request.contextHandle)
            ?: throw notFound("context", request.contextHandle)
        val context = registration.context
        val selections = if (request.hasSelections()) {
            reconstructSelections(request.selections, context)
        } else {
            SelectionsRegistry.get(request.selectionsHandle)
                ?: throw notFound("selections", request.selectionsHandle)
        }
        val objectDataJson = try {
            withRestoredCoroutineContext(registration.coroutineContext) {
                val result = context.resolveSelectionSet(selections, options)
                EngineObjectDataSerializer.serialize(result)
            }
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

    private fun reconstructSelections(
        proto: SerializedSelectionSet,
        context: EngineExecutionContext
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

    /**
     * Restores request-scoped coroutine elements while retaining the callback Job and dispatcher.
     * Installs a local thread context when no request context was captured.
     */
    private suspend fun <T> withRestoredCoroutineContext(
        capturedContext: CoroutineContext?,
        block: suspend () -> T,
    ): T {
        val callbackContext = currentCoroutineContext()
        val propagatedContext = capturedContext
            ?.minusKey(Job)
            ?.minusKey(ContinuationInterceptor.Key)
        val context = callbackContext + (propagatedContext ?: kotlin.coroutines.EmptyCoroutineContext)
        if (context[ThreadLocalCoroutineContextManager.ContextElement] != null) {
            return withContext(context) { block() }
        }

        val defaultJob = SupervisorJob(context[Job])
        return try {
            withContext(
                context + ThreadLocalCoroutineContextManager.ContextElement(defaultJob)
            ) {
                block()
            }
        } finally {
            defaultJob.complete()
        }
    }

    private fun notFound(
        kind: String,
        handle: String
    ): StatusRuntimeException = Status.NOT_FOUND.withDescription("$kind handle not registered: $handle").asRuntimeException()
}
