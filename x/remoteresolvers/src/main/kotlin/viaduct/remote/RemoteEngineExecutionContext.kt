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
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * [EngineExecutionContext] used by [RemoteResolverService]. Forwards re-entrant
 * [resolveSelectionSetSync] calls to the engine over gRPC. When [delegate] is `null`
 * (cross-JVM mode) members that need local engine state throw; [localSchema] and
 * [GlobalIDCodecDefault] cover the common cases.
 */
class RemoteEngineExecutionContext(
    private val delegate: EngineExecutionContext?,
    private val callbackChannel: ManagedChannel,
    private val contextHandle: String,
    private val localSchema: ViaductSchema? = null
) : EngineExecutionContext {
    private val callbackStub = EngineCallbackServiceGrpcKt.EngineCallbackServiceCoroutineStub(callbackChannel)

    private fun requireDelegate(operation: String): EngineExecutionContext = delegate ?: throw UnsupportedOperationException("'$operation' requires a local engine context")

    override val fullSchema: ViaductSchema
        get() = localSchema ?: requireDelegate("fullSchema").fullSchema

    override val scopedSchema: ViaductSchema
        get() = localSchema ?: requireDelegate("scopedSchema").scopedSchema

    override val activeSchema: ViaductSchema
        get() = localSchema ?: requireDelegate("activeSchema").activeSchema

    override val requestContext: Any?
        get() = delegate?.requestContext

    override val engine: Engine
        get() = requireDelegate("engine").engine

    override val executionHandle: EngineExecutionContext.ExecutionHandle?
        get() = delegate?.executionHandle

    // The default codec is stateless and uses the same format on both sides; safe to
    // construct without round-tripping to the original context.
    override val globalIDCodec: GlobalIDCodec
        get() = delegate?.globalIDCodec ?: GlobalIDCodecDefault

    override val fieldScope: EngineExecutionContext.FieldExecutionScope
        get() = requireDelegate("fieldScope").fieldScope

    override val engineSelectionSetFactory: EngineSelectionSet.Factory
        get() = requireDelegate("engineSelectionSetFactory").engineSelectionSetFactory

    override fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType
    ): NodeReference = requireDelegate("createNodeReference").createNodeReference(id, graphQLObjectType)

    override fun createRootFieldReference(
        rootFieldPath: List<String>,
        type: GraphQLObjectType,
        args: Map<String, Any?>
    ): RootFieldReference = requireDelegate("createRootFieldReference").createRootFieldReference(rootFieldPath, type, args)

    override fun hasModernNodeResolver(typeName: String): Boolean = delegate?.hasModernNodeResolver(typeName) ?: false

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

    override suspend fun resolveSelectionSetSync(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions
    ): EngineObjectData.Sync {
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

    private companion object {
        // Type identity isn't propagated over the wire; the receiver-side builder just
        // needs a name to attach to the deserialized result.
        private val REMOTE_RESULT_TYPE = graphql.schema.GraphQLObjectType.newObject()
            .name("RemoteQueryResult")
            .build()
    }
}
