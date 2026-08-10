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
 * [EngineExecutionContext] shared by both remote-resolver transports: unary gRPC
 * ([UnaryRemoteEngineExecutionContext]) and bidirectional streaming
 * ([RemoteResolverStreamExecutionContext]). Neither transport carries a live engine to resolve
 * against off this JVM, so every member but [resolveSelectionSet] is schema-only when [delegate]
 * is `null`: members that need local engine state throw; [localSchema] and [GlobalIDCodecDefault]
 * cover the common cases. Only how a re-entrant ctx.query()/ctx.mutation() call
 * ([resolveSelectionSet]) reaches back to the engine differs by transport -- unary calls back over
 * a persistent gRPC channel keyed by a context handle, streaming dispatches over the request's
 * own stream -- so it's the one abstract member.
 */
abstract class RemoteEngineExecutionContext(
    private val delegate: EngineExecutionContext?,
    private val localSchema: ViaductSchema? = null
) : EngineExecutionContext {
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

    // With no delegate, build a schema-only factory from localSchema so a remotely-run resolver can
    // reconstruct sub-selection sets shipped over the wire. Mirrors the delegate-first
    // createNodeReference / globalIDCodec fallback (delegate and localSchema are mutually exclusive —
    // callers set localSchema only when there's no delegate — so the order doesn't matter).
    // Memoized: one factory per context instance.
    private val localSelectionSetFactory: EngineSelectionSet.Factory? by lazy {
        localSchema?.let { EngineSelectionSetFactoryImpl(it) }
    }

    override val engineSelectionSetFactory: EngineSelectionSet.Factory
        get() = delegate?.engineSelectionSetFactory
            ?: localSelectionSetFactory
            ?: throw UnsupportedOperationException("'engineSelectionSetFactory' requires a local engine context or schema")

    // A resolver running remotely may build a node reference (e.g. `ctx.nodeRef(...)`). Without a
    // local engine there is no way to construct a fully-resolvable reference, but a resolver-produced
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

    abstract override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions
    ): EngineObjectData.Sync
}

/**
 * The unary-gRPC [RemoteEngineExecutionContext]: forwards [resolveSelectionSet] to the engine over
 * a persistent callback channel, keyed by [contextHandle] so the far side can resolve back against
 * a process-local [viaduct.remote.registry.ContextRegistry] entry.
 */
class UnaryRemoteEngineExecutionContext(
    delegate: EngineExecutionContext?,
    callbackChannel: ManagedChannel,
    private val contextHandle: String,
    localSchema: ViaductSchema? = null
) : RemoteEngineExecutionContext(delegate, localSchema) {
    private val callbackStub = EngineCallbackServiceGrpcKt.EngineCallbackServiceCoroutineStub(callbackChannel)

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
        // The result's root type is the selection set's own type, which is known locally — no need to
        // trust (or invent) a type name for it. Resolve nested names against this receiver's own
        // schema, like every other decode site (selectionSet.schema is equivalent today but throws
        // outright for the empty selection set the RRS substitutes on a handle miss).
        return EngineObjectDataSerializer.deserialize(
            response.objectDataJson.toByteArray(),
            fullSchema.schema,
            selectionSet.type
        )
    }
}

// Minimal [NodeReference] for a context without a delegate: carries only id + type (the
// [EngineObject.type] used to tag the wire value). It is never resolved on the service side —
// the engine side rebuilds a resolvable reference via its own `createNodeReference`.
private class RemoteNodeReference(
    override val id: String,
    override val type: GraphQLObjectType
) : NodeReference
