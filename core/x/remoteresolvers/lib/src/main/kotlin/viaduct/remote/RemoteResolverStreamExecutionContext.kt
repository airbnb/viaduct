package viaduct.remote

import com.google.protobuf.ByteString
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ViaductSchema
import viaduct.remote.grpc.SerializedSelectionSet

/**
 * The streaming-transport [RemoteEngineExecutionContext]: always schema-only (`delegate = null`
 * on the base) -- the streaming design has no context_handle to resolve against a process-local
 * registry, so a remotely-run resolver's re-entrant calls always cross the wire via [dispatcher],
 * never a local shortcut. [resolveSelectionSet] is the only member that needs an override here;
 * every other member (schema access, node references, global ID codec, etc.) is inherited as-is
 * from the base's no-delegate fallback path. (Contrast with [UnaryRemoteEngineExecutionContext],
 * the unary transport's context, which does support a delegate for same-JVM testing.)
 */
internal class RemoteResolverStreamExecutionContext(
    private val dispatcher: CallbackDispatcher,
    private val resolverId: String,
    localSchema: ViaductSchema
) : RemoteEngineExecutionContext(delegate = null, localSchema = localSchema) {
    // The re-entrant ctx.query()/ctx.mutation() path: serialize the selection set's content (not a
    // handle) and dispatch it as a CallbackRequest on the stream this context is bound to.
    override suspend fun resolveSelectionSet(
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions
    ): EngineObjectData.Sync {
        // .document and .variables each independently call toFragment(); capture it once.
        val fragment = selectionSet.toFragment()
        val serialized = SerializedSelectionSet.newBuilder()
            .setType(selectionSet.type)
            .setDocument(fragment.document)
            .setVariablesJson(ByteString.copyFrom(FieldValueSerializer.serializeArguments(fragment.variables.asMap())))
            .build()
        val response = dispatcher.call(
            selections = serialized,
            resolverId = resolverId,
            isMutation = options.operationType == Engine.OperationType.MUTATION
        )
        if (response.hasError()) {
            throw RemoteCallbackException(response.error.message, response.error.errorType)
        }
        // The result's root type is whatever selectionSet was rooted on (e.g. "Query"/"Mutation").
        // It must be the real type from the schema, not a freshly-built placeholder with no field
        // definitions: generated GRT field access (ObjectBase.get) looks up the field on this exact
        // GraphQLObjectType, and would fail with "Field ... not found" for every field otherwise.
        val resultType = fullSchema.schema.getObjectType(selectionSet.type)
        return EngineObjectDataSerializer.deserialize(response.objectDataJson.toByteArray(), resultType)
    }
}

/** Thrown when a CallbackRequest fails on the other side of the stream. */
internal class RemoteCallbackException(
    message: String?,
    val remoteErrorType: String
) : RuntimeException(message)
