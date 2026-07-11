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
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.remote.grpc.EngineCallbackServiceGrpcKt
import viaduct.remote.grpc.QueryRequest
import viaduct.remote.registry.SelectionsRegistry
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

/**
 * [EngineExecutionContext] used by [RemoteResolverService]. Forwards re-entrant
 * [resolveSelectionSet] calls to the engine over gRPC. When [delegate] is `null`
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

    // Network mode has no delegate; build a schema-only factory from localSchema so a remotely-run
    // resolver can reconstruct sub-selection sets shipped over the wire. Mirrors the delegate-first
    // createNodeReference / globalIDCodec fallback (delegate and localSchema are mutually exclusive —
    // buildRemoteContext sets localSchema only when there's no delegate — so the order doesn't matter).
    // Memoized: one factory per context instance.
    private val localSelectionSetFactory: EngineSelectionSet.Factory? by lazy {
        localSchema?.let { EngineSelectionSetFactoryImpl(it) }
    }

    override val engineSelectionSetFactory: EngineSelectionSet.Factory
        get() = delegate?.engineSelectionSetFactory
            ?: localSelectionSetFactory
            ?: throw UnsupportedOperationException("'engineSelectionSetFactory' requires a local engine context or schema")

    // A resolver running remotely may build a node reference (e.g. `ctx.nodeRef(...)`). In network
    // mode there is no local engine to construct a fully-resolvable reference, but a resolver-produced
    // reference only needs to carry its id + type to the wire — the engine side rebuilds a live
    // reference on receipt — so a lightweight holder suffices and avoids requiring engine state here.
    override fun createNodeReference(
        id: String,
        graphQLObjectType: GraphQLObjectType
    ): NodeReference = delegate?.createNodeReference(id, graphQLObjectType) ?: RemoteNodeReference(id, graphQLObjectType)

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

    override suspend fun resolveSelectionSet(
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

    // Minimal [NodeReference] for network mode: carries only id + type (the [EngineObject.type] used
    // by [FieldValueSerializer] to tag the wire value). It is never resolved on the service side —
    // the engine side rebuilds a resolvable reference via its own `createNodeReference`.
    private class RemoteNodeReference(
        override val id: String,
        override val type: GraphQLObjectType
    ) : NodeReference

    private companion object {
        // Type identity isn't propagated over the wire; the receiver-side builder just
        // needs a name to attach to the deserialized result.
        private val REMOTE_RESULT_TYPE = graphql.schema.GraphQLObjectType.newObject()
            .name("RemoteQueryResult")
            .build()
    }
}
