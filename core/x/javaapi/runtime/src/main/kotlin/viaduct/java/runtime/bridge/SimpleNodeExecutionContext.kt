package viaduct.java.runtime.bridge

import graphql.language.FragmentDefinition
import graphql.schema.GraphQLInputObjectType
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.errors.handleFrameworkErrors
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.context.SelectiveNodeExecutionContext
import viaduct.java.api.documents.MutationFromAnnotation
import viaduct.java.api.documents.QueryFromAnnotation
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.reflect.RootObjectField
import viaduct.java.api.reflect.Type
import viaduct.java.api.resolvers.NodeResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.NodeCompositeOutput
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Concrete [NodeExecutionContext] for Java node resolvers.
 *
 * Bridges the engine's serialized GlobalID string to the typed Java [GlobalID] interface and
 * provides full implementations of [query], [mutation], and [nodeRef] (mirroring
 * [SimpleFieldExecutionContext] for the field resolver side).
 *
 * Also implements [SelectiveNodeExecutionContext] so the same instance can serve both
 * non-selective and selective generated Context wrappers.
 *
 * @param serializedId the serialized GlobalID string (e.g. "NodeObj:tenant1")
 * @param typeName the GraphQL type name this resolver handles (e.g. "NodeObj")
 * @param requestContext the per-request context object
 * @param engineExecutionContext the engine execution context, required for ctx.query, ctx.mutation
 *     and ctx.nodeRef
 * @param coroutineScope the coroutine scope for launching subquery coroutines
 * @param grtPackagePrefix package containing generated GRT classes
 */
@Suppress("UNCHECKED_CAST")
class SimpleNodeExecutionContext(
    private val serializedId: String,
    private val typeName: String,
    private val requestContext: Any?,
    private val engineExecutionContext: EngineExecutionContext? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val grtPackagePrefix: String? = null,
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
) : NodeExecutionContext<NodeObject>,
    SelectiveNodeExecutionContext<NodeObject>,
    NodeResolverBase.Context<NodeObject>,
    InternalContext {
    private val delegate = JavaEngineContextDelegate(engineExecutionContext, grtPackagePrefix, coroutineScope, knownFragments)

    override fun getId(): GlobalID<NodeObject> {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("getId requires engineExecutionContext.")
        val (_, internalId) = codec.deserialize(serializedId)
        return GlobalIDImpl(type = typeFromName(typeName, grtPackagePrefix), internalId = internalId)
    }

    override fun getRequestContext(): Any? = requestContext

    // ── InternalContext implementation (delegated to JavaEngineContextDelegate) ──

    override fun getSchema(): ViaductSchema = delegate.getSchema()

    override fun getArgumentsInputType(
        name: String,
        containingTypeName: String,
        fieldName: String,
    ): GraphQLInputObjectType = delegate.getArgumentsInputType(name, containingTypeName, fieldName)

    override fun getGlobalIDCodec(): GlobalIDCodec = delegate.getGlobalIDCodec()

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> = delegate.deserializeGlobalID(serialized)

    override fun <T : NodeCompositeOutput> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> = delegate.globalIDFor(type, internalID)

    override fun <T : NodeCompositeOutput> serialize(globalID: GlobalID<T>): String = delegate.serialize(globalID)

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String
    ): String = delegate.globalIDStringFor(type, internalID)

    @Suppress("UNCHECKED_CAST")
    override fun <T : NodeCompositeOutput> nodeRef(id: GlobalID<T>): T {
        val grtClass = id.getType().getJavaClass() as Class<T>
        return delegate.nodeRef(id, grtClass)
    }

    override fun <A : Arguments, T : GraphQLObject> rootFieldRef(
        field: RootObjectField<*, T, A>,
        arguments: A,
    ): T = delegate.rootFieldRef(field, arguments)

    override fun <T : Any> query(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> = delegate.query(selections, variables, targetClass)

    override fun <T : Any> mutation(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> = delegate.mutation(selections, variables, targetClass)

    override fun <T : Any> query(
        operation: QueryFromAnnotation,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> = delegate.queryOperation(operation.operationText, variables, targetClass)

    override fun <T : Any> mutation(
        operation: MutationFromAnnotation,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> = delegate.mutationOperation(operation.operationText, variables, targetClass)

    override fun selections(): Any =
        handleFrameworkErrors("selections") {
            throw FrameworkException("selections() not yet implemented for selective Java node resolvers")
        }
}
