package viaduct.java.runtime.bridge

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.NodeReference
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.errors.FrameworkException
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.context.SelectiveNodeExecutionContext
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.reflect.Type
import viaduct.java.api.resolvers.NodeResolverBase
import viaduct.java.api.types.NodeCompositeOutput
import viaduct.java.api.types.NodeObject

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
 */
@Suppress("UNCHECKED_CAST")
class SimpleNodeExecutionContext(
    private val serializedId: String,
    private val typeName: String,
    private val requestContext: Any?,
    private val engineExecutionContext: EngineExecutionContext? = null,
    private val coroutineScope: CoroutineScope? = null,
) : NodeExecutionContext<NodeObject>,
    SelectiveNodeExecutionContext<NodeObject>,
    NodeResolverBase.Context<NodeObject> {
    override fun getId(): GlobalID<NodeObject> {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("getId requires engineExecutionContext.")
        val (_, internalId) = codec.deserialize(serializedId)
        return GlobalIDImpl(type = typeFromName(typeName), internalId = internalId)
    }

    override fun getRequestContext(): Any? = requestContext

    override fun <T : NodeCompositeOutput> globalIDFor(
        type: Type<T>,
        internalID: String
    ): GlobalID<T> {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("globalIDFor requires engineExecutionContext.")
        return codec.createGlobalID(type, internalID)
    }

    override fun <T : NodeCompositeOutput> serialize(globalID: GlobalID<T>): String {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("serialize requires engineExecutionContext.")
        return codec.serializeGlobalID(globalID)
    }

    override fun <T : NodeObject> globalIDStringFor(
        type: Type<T>,
        internalID: String
    ): String {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("globalIDStringFor requires engineExecutionContext.")
        return codec.serialize(type.name, internalID)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : NodeCompositeOutput> nodeRef(id: GlobalID<T>): T {
        val engineCtx = engineExecutionContext
            ?: throw FrameworkException("nodeRef requires engineExecutionContext.")
        val refTypeName = id.getType().name
        val serializedId = engineCtx.globalIDCodec.serializeGlobalID(id)
        val graphqlType = engineCtx.activeSchema.schema.getObjectType(refTypeName)
            ?: throw FrameworkException("GraphQL type '$refTypeName' not found in schema for nodeRef.")
        val nodeReference = engineCtx.createNodeReference(serializedId, graphqlType)
        val grtClass = id.getType().getJavaClass() as Class<T>
        return grtClass.getDeclaredConstructor(NodeReference::class.java).newInstance(nodeReference) as T
    }

    override fun <T : Any> query(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> {
        val engineCtx = engineExecutionContext
            ?: throw FrameworkException(
                "ctx.query() requires engineExecutionContext. Ensure the resolver is running within a live execution context."
            )
        val scope = coroutineScope
            ?: throw FrameworkException("ctx.query() requires a coroutineScope.")
        return scope.future {
            handleFrameworkErrorsSuspend("query") {
                val queryTypeName = engineCtx.activeSchema.schema.queryType.name
                val selectionSet = engineCtx.engineSelectionSetFactory.engineSelectionSet(
                    queryTypeName,
                    selections,
                    JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(variables, engineCtx)
                )
                val result = engineCtx.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.DEFAULT)
                @Suppress("UNCHECKED_CAST")
                convertSyncEngineDataToJavaObject(targetClass, result) as T
            }
        }
    }

    override fun <T : Any> mutation(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>,
    ): CompletableFuture<T> {
        val engineCtx = engineExecutionContext
            ?: throw FrameworkException(
                "ctx.mutation() requires engineExecutionContext. Ensure the resolver is running within a live execution context."
            )
        val scope = coroutineScope
            ?: throw FrameworkException("ctx.mutation() requires a coroutineScope.")
        return scope.future {
            handleFrameworkErrorsSuspend("mutation") {
                val mutationType = engineCtx.activeSchema.schema.mutationType
                    ?: throw FrameworkException("ctx.mutation() is not available: the schema has no Mutation type.")
                val selectionSet = engineCtx.engineSelectionSetFactory.engineSelectionSet(
                    mutationType.name,
                    selections,
                    JavaTenantApiInputValueNormalizer.normalizeVariablesForEngine(variables, engineCtx)
                )
                val result = engineCtx.resolveSelectionSet(selectionSet, ResolveSelectionSetOptions.MUTATION)
                @Suppress("UNCHECKED_CAST")
                convertSyncEngineDataToJavaObject(targetClass, result) as T
            }
        }
    }

    override fun selections(): Any =
        handleFrameworkErrors("selections") {
            throw FrameworkException("selections() not yet implemented for selective Java node resolvers")
        }
}
