package viaduct.remote

import graphql.schema.GraphQLObjectType
import io.grpc.ManagedChannel
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.remote.grpc.EngineCallbackServiceGrpcKt
import viaduct.remote.grpc.QueryRequest
import viaduct.remote.registry.SelectionsRegistry
import viaduct.service.api.spi.GlobalIDCodec

/**
 * [EngineExecutionContext] that forwards [resolveSelectionSet] over gRPC to an
 * [EngineCallbackService] so resolvers running in a [RemoteResolverService] can
 * re-enter the engine. All other members delegate to [delegate] when present,
 * or throw [UnsupportedOperationException] otherwise.
 */
class RemoteEngineExecutionContext(
    private val delegate: EngineExecutionContext?,
    private val callbackChannel: ManagedChannel,
    private val contextHandle: String
) : EngineExecutionContext {
    private val callbackStub = EngineCallbackServiceGrpcKt.EngineCallbackServiceCoroutineStub(callbackChannel)

    private fun requireDelegate(operation: String): EngineExecutionContext =
        delegate ?: throw UnsupportedOperationException(
            "Operation '$operation' requires local context (delegate is null)."
        )

    // Schema properties - delegate to original context
    override val fullSchema: ViaductSchema
        get() = requireDelegate("fullSchema").fullSchema

    override val scopedSchema: ViaductSchema
        get() = requireDelegate("scopedSchema").scopedSchema

    override val activeSchema: ViaductSchema
        get() = requireDelegate("activeSchema").activeSchema

    // Request context - delegate to original context
    override val requestContext: Any?
        get() = delegate?.requestContext

    // Engine reference - delegate to original context
    override val engine: Engine
        get() = requireDelegate("engine").engine

    // Execution handle - delegate to original context
    override val executionHandle: EngineExecutionContext.ExecutionHandle?
        get() = delegate?.executionHandle

    // GlobalID codec - delegate to original context
    override val globalIDCodec: GlobalIDCodec
        get() = requireDelegate("globalIDCodec").globalIDCodec

    // Field execution scope - delegate to original context
    override val fieldScope: EngineExecutionContext.FieldExecutionScope
        get() = requireDelegate("fieldScope").fieldScope

    // Factory for creating selection sets - delegate to original context
    override val engineSelectionSetFactory: EngineSelectionSet.Factory
        get() = requireDelegate("engineSelectionSetFactory").engineSelectionSetFactory

    // Node reference creation - delegate to original context
    override fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType
    ): NodeReference = requireDelegate("createNodeReference").createNodeReference(id, graphQLObjectType)

    // Root field reference creation - delegate to original context
    override fun createRootFieldReference(
        rootFieldPath: List<String>,
        type: GraphQLObjectType,
        args: Map<String, Any?>
    ): RootFieldReference = requireDelegate("createRootFieldReference").createRootFieldReference(rootFieldPath, type, args)

    // Modern node resolver check - delegate to original context
    override fun hasModernNodeResolver(typeName: String): Boolean = delegate?.hasModernNodeResolver(typeName) ?: false

    // completeSelectionSet - delegate to original context
    override suspend fun completeSelectionSet(
        selectionSet: RequiredSelectionSet,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions
    ): graphql.ExecutionResult = requireDelegate("completeSelectionSet").completeSelectionSet(selectionSet, arguments, options)

    override suspend fun completeSelectionSet(
        selectionSet: RequiredSelectionSet,
        targetResult: ObjectEngineResult,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions
    ): graphql.ExecutionResult = requireDelegate("completeSelectionSet").completeSelectionSet(selectionSet, targetResult, arguments, options)

    /** Forwards to the engine over gRPC via the callback channel. */
    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions
    ): EngineObjectData {
        val selectionsHandle = SelectionsRegistry.register(selectionSet)
        val request = QueryRequest.newBuilder()
            .setContextHandle(contextHandle)
            .setSelectionsHandle(selectionsHandle)
            .build()
        val response = try {
            if (options.operationType == Engine.OperationType.MUTATION) {
                callbackStub.executeMutation(request)
            } else {
                callbackStub.executeQuery(request)
            }
        } finally {
            SelectionsRegistry.unregister(selectionsHandle)
        }
        return EngineObjectDataSerializer.deserialize(response.objectDataJson.toByteArray(), REMOTE_RESULT_TYPE)
    }

    // Sync selection-set resolution - delegate to original context
    override suspend fun resolveSelectionSetSync(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions
    ): EngineObjectData.Sync = requireDelegate("resolveSelectionSetSync").resolveSelectionSetSync(selectionSet, options)

    private companion object {
        // Type identity is not propagated over the wire; synthesize a minimal type once
        // so the receiver-side builder has a name to attach.
        private val REMOTE_RESULT_TYPE = graphql.schema.GraphQLObjectType.newObject()
            .name("RemoteQueryResult")
            .build()
    }
}
