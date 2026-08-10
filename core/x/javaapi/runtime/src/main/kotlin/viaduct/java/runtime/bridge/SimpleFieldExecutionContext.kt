package viaduct.java.runtime.bridge

import graphql.language.FragmentDefinition
import graphql.schema.GraphQLInputObjectType
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ViaductSchema
import viaduct.errors.FrameworkException
import viaduct.errors.handleFrameworkErrors
import viaduct.java.api.context.FieldExecutionContext
import viaduct.java.api.context.SelectiveFieldExecutionContext
import viaduct.java.api.documents.MutationFromAnnotation
import viaduct.java.api.documents.QueryFromAnnotation
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.internal.ResolverClassFinder
import viaduct.java.api.reflect.RootObjectField
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
    private val knownFragments: Map<String, FragmentDefinition> = emptyMap(),
) : FieldExecutionContext<GraphQLObject, Query, Arguments, AnySelections>,
    SelectiveFieldExecutionContext<AnySelections>,
    FieldResolverBase.Context<GraphQLObject, Query, Arguments, AnySelections>,
    InternalContext {
    private val delegate = JavaEngineContextDelegate(engineExecutionContext, classFinder, coroutineScope, knownFragments)

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
        return arguments ?: Arguments.None
    }

    override fun getSelections(): Any {
        throw FrameworkException("Selections access not yet implemented for Java resolvers")
    }

    override fun getRequestContext(): Any? = requestContext

    // ── InternalContext implementation ──
    // Delegates to the shared JavaEngineContextDelegate, mirroring Kotlin's ExecutionContextImpl
    // which implements both ExecutionContext and InternalContext.

    override fun getSchema(): ViaductSchema = delegate.getSchema()

    override fun getArgumentsInputType(
        name: String,
        containingTypeName: String,
        fieldName: String,
    ): GraphQLInputObjectType = delegate.getArgumentsInputType(name, containingTypeName, fieldName)

    override fun getGlobalIDCodec(): GlobalIDCodec = delegate.getGlobalIDCodec()

    override fun getClassFinder(): ResolverClassFinder = delegate.getClassFinder()

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> deserializeGlobalID(serialized: String): viaduct.java.api.globalid.GlobalID<T> = delegate.deserializeGlobalID(serialized)

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> globalIDFor(
        type: viaduct.java.api.reflect.Type<T>,
        internalID: String
    ): viaduct.java.api.globalid.GlobalID<T> = delegate.globalIDFor(type, internalID)

    override fun <T : viaduct.java.api.types.NodeCompositeOutput> serialize(globalID: viaduct.java.api.globalid.GlobalID<T>): String = delegate.serialize(globalID)

    override fun <T : viaduct.java.api.types.NodeObject> globalIDStringFor(
        type: viaduct.java.api.reflect.Type<T>,
        internalID: String
    ): String = delegate.globalIDStringFor(type, internalID)

    @Suppress("UNCHECKED_CAST")
    override fun <T : viaduct.java.api.types.NodeCompositeOutput> nodeRef(id: viaduct.java.api.globalid.GlobalID<T>): T {
        // Field contexts resolve the GRT class via the classFinder, falling back to the GlobalID
        // type's Java class.
        val typeName = id.getType().name
        val grtClass = classFinder?.grtClassForName(typeName) as? Class<T>
            ?: id.getType().getJavaClass() as Class<T>
        return delegate.nodeRef(id, grtClass)
    }

    override fun <A : Arguments, T : GraphQLObject> rootFieldRef(
        field: RootObjectField<*, T, A>,
        arguments: A,
    ): T = delegate.rootFieldRef(field, arguments)

    override fun <T : Any> query(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>
    ): CompletableFuture<T> = delegate.query(selections, variables, targetClass)

    override fun <T : Any> mutation(
        selections: String,
        variables: Map<String, Any?>,
        targetClass: Class<T>
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
}
