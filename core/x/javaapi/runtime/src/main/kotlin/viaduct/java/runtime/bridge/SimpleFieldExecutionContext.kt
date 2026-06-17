package viaduct.java.runtime.bridge

import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.context.SelectiveFieldExecutionContext
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.GraphQLObject
import viaduct.java.api.types.Query
import viaduct.service.api.spi.GlobalIDCodec

// Internal marker type for the selections type parameter
object AnySelections : CompositeOutput

/**
 * Minimal implementation of FieldExecutionContext for Java resolvers.
 *
 * Bridges the engine's untyped data (argument maps) to the Java API's typed interfaces.
 * Arguments are populated from the engine's argument map using reflection on the Arguments class.
 *
 * Also implements [InternalContext] so that tenant code can pass `this` as an ExecutionContext
 * to generated builders (`MyType.builder(ctx)`) and the `InternalContext.from(ctx)` cast succeeds.
 * This mirrors Kotlin's `ExecutionContextImpl` which implements both `ExecutionContext` and
 * `InternalContext`.
 *
 * Uses [Arguments] directly as the generic argument type so that any concrete Arguments
 * subtype (e.g., Query_person_Arguments) can be returned without a ClassCastException.
 * Callers access getArguments() through the erased interface and cast to their specific type.
 *
 * @param requestContext The request context from the engine
 * @param arguments The typed Arguments instance (populated from the engine's argument map), or null
 * @param objectValue The parent object value (e.g., a Person instance for a Person.fullAddress resolver), or null
 * @param queryValue The query root value (populated from the queryValueFragment result), or null
 * @param engineExecutionContext The engine execution context, required for ctx.query() and ctx.mutation()
 * @param coroutineScope The coroutine scope for launching subquery coroutines, required for ctx.query() and ctx.mutation()
 * @param classFinder Resolves GRT classes by type name; used to build the [InternalContext] attached
 *        to GRTs returned by ctx.query()/ctx.mutation() and ctx.nodeRef(). May be null outside a
 *        live execution context.
 */
@Suppress("UNCHECKED_CAST", "TooManyFunctions")
class SimpleFieldExecutionContext(
    private val requestContext: Any?,
    private val arguments: Arguments? = null,
    private val objectValue: Any? = null,
    private val queryValue: Any? = null,
    private val engineExecutionContext: EngineExecutionContext? = null,
    private val coroutineScope: CoroutineScope? = null,
    private val classFinder: ResolverClassFinder? = null,
) : FieldExecutionContext<GraphQLObject, Query, Arguments, AnySelections>,
    SelectiveFieldExecutionContext<AnySelections>,
    FieldResolverBase.Context<GraphQLObject, Query, Arguments, AnySelections>,
    InternalContext {
    override fun getObjectValue(): GraphQLObject =
        handleFrameworkErrors("getObjectValue") {
            objectValue as? GraphQLObject
                ?: throw FrameworkException(
                    "Object value not available. Ensure the resolver declares an objectValueFragment."
                )
        }

    override fun getQueryValue(): Query =
        handleFrameworkErrors("getQueryValue") {
            queryValue as? Query
                ?: throw FrameworkException("Query value not available.")
        }

    override fun getArguments(): Arguments {
        return arguments ?: Arguments.NoArguments
    }

    override fun getSelections(): Any {
        throw FrameworkException("Selections access not yet implemented for Java resolvers")
    }

    override fun getRequestContext(): Any? = requestContext

    // ── InternalContext implementation ──
    // Delegates to the engine context's schema/codec/classFinder, mirroring Kotlin's
    // ExecutionContextImpl which implements both ExecutionContext and InternalContext.

    override fun getSchema(): ViaductSchema {
        return engineExecutionContext?.fullSchema
            ?: throw FrameworkException("getSchema() requires engineExecutionContext.")
    }

    override fun getGlobalIDCodec(): GlobalIDCodec {
        return engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("getGlobalIDCodec() requires engineExecutionContext.")
    }

    override fun getClassFinder(): ResolverClassFinder {
        return classFinder
            ?: throw FrameworkException("getClassFinder() requires classFinder.")
    }

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> globalIDFor(
        type: viaduct.java.api.reflect.Type<T>,
        internalID: String
    ): viaduct.java.api.globalid.GlobalID<T> {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("globalIDFor requires engineExecutionContext.")
        return codec.createGlobalID(type, internalID)
    }

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> serialize(globalID: viaduct.java.api.globalid.GlobalID<T>): String {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("serialize requires engineExecutionContext.")
        return codec.serializeGlobalID(globalID)
    }

    override fun <T : viaduct.java.api.types.NodeObject> globalIDStringFor(
        type: viaduct.java.api.reflect.Type<T>,
        internalID: String
    ): String {
        val codec = engineExecutionContext?.globalIDCodec
            ?: throw FrameworkException("globalIDStringFor requires engineExecutionContext.")
        return codec.serialize(type.name, internalID)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : viaduct.java.api.types.NodeCompositeOutput> nodeRef(id: viaduct.java.api.globalid.GlobalID<T>): T {
        val engineCtx = engineExecutionContext
            ?: throw FrameworkException("nodeRef requires engineExecutionContext.")
        val typeName = id.getType().name
        val serializedId = engineCtx.globalIDCodec.serializeGlobalID(id)
        val graphqlType = engineCtx.activeSchema.schema.getObjectType(typeName)
            ?: throw FrameworkException("GraphQL type '$typeName' not found in schema for nodeRef.")
        val nodeReference = engineCtx.createNodeReference(serializedId, graphqlType)
        val grtClass = id.getType().getJavaClass() as Class<T>
        val internalContext = classFinder?.let { buildInternalContext(engineCtx, it) }
        return grtClass
            .getDeclaredConstructor(InternalContext::class.java, viaduct.engine.api.NodeReference::class.java)
            .newInstance(internalContext, nodeReference) as T
    }

    override fun <T : Any> query(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>
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
                convertSyncEngineDataToJavaObject(
                    targetClass,
                    result,
                    classFinder?.let { buildInternalContext(engineCtx, it) }
                ) as T
            }
        }
    }

    override fun <T : Any> mutation(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>
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
                convertSyncEngineDataToJavaObject(
                    targetClass,
                    result,
                    classFinder?.let { buildInternalContext(engineCtx, it) }
                ) as T
            }
        }
    }
}
