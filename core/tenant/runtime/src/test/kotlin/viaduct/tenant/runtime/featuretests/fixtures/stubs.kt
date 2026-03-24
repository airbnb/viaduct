package viaduct.tenant.runtime.featuretests.fixtures

import graphql.schema.GraphQLSchema
import javax.inject.Provider
import viaduct.api.FieldValue
import viaduct.api.NodeResolverBase
import viaduct.api.ResolverBase
import viaduct.api.VariablesProvider
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.ConnectionFieldExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Connection
import viaduct.api.types.ConnectionArguments
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.api.spi.CheckerExecutor
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.bootstrap.RequiredSelectionSetFactory
import viaduct.tenant.runtime.context.ConnectionFieldExecutionContextImpl
import viaduct.tenant.runtime.context.FieldExecutionContextImpl
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/** Common interface for field resolver stubs (regular and connection) used by the bootstrapper. */
interface FieldResolverStub {
    val coord: Coordinate
    val resolverName: String?
    val resolver: Provider<out ResolverBase<*>>

    suspend fun resolve(
        self: Any,
        ctx: Any
    ): Any?

    fun resolverFactory(
        schema: ViaductSchema,
        reflectionLoader: ReflectionLoader
    ): FieldExecutionContextFactory

    fun requiredSelectionSets(
        coord: Coordinate,
        schema: GraphQLSchema,
        reflectionLoader: ReflectionLoader
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?>
}

/**
 * Shared implementation for field resolver stubs. Subclasses only need to define a nested
 * `Context` class whose type signature tells [FieldExecutionContextFactory.of] whether to
 * create a [FieldExecutionContextImpl] or [ConnectionFieldExecutionContextImpl].
 */
@Suppress("UNUSED_PARAMETER", "UNCHECKED_CAST")
abstract class AbstractFieldUnbatchedResolverStub<Ctx : BaseFieldExecutionContext<*, *, *>>(
    val objectSelections: ParsedSelections? = null,
    val querySelections: ParsedSelections? = null,
    override val coord: Coordinate,
    val variables: List<SelectionSetVariable>,
    val resolveFn: (suspend (ctx: Any) -> Any?),
    val variablesProvider: VariablesProviderInfo?,
    override val resolverName: String?
) : ResolverBase<Any?>, FieldResolverStub {
    override suspend fun resolve(
        self: Any,
        ctx: Any
    ) = resolveFn(ctx)

    override val resolver: Provider<out ResolverBase<*>> = Provider { this }

    override fun resolverFactory(
        schema: ViaductSchema,
        reflectionLoader: ReflectionLoader
    ): FieldExecutionContextFactory =
        FieldExecutionContextFactory.of(
            resolverBaseClass = this::class.java,
            globalIDCodec = GlobalIDCodecDefault,
            reflectionLoader = reflectionLoader,
            schema = schema,
            typeName = coord.first,
            fieldName = coord.second,
            grtConvFactory = DefaultGRTConvFactory,
        )

    override fun requiredSelectionSets(
        coord: Coordinate,
        schema: GraphQLSchema,
        reflectionLoader: ReflectionLoader
    ): Pair<RequiredSelectionSet?, RequiredSelectionSet?> {
        val variablesProviderContextFactory = resolverFactory(ViaductSchema(schema), reflectionLoader)

        val factory = RequiredSelectionSetFactory(GlobalIDCodecDefault, reflectionLoader)
        return factory.createRequiredSelectionSets(
            variablesProvider = variablesProvider,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = variables,
            attribution = resolverName?.let { ExecutionAttribution.fromResolver(it) },
        )
    }
}

/** Resolver stub for regular (non-connection) fields. */
class FieldUnbatchedResolverStub<Ctx : BaseFieldExecutionContext<*, *, *>>(
    objectSelections: ParsedSelections? = null,
    querySelections: ParsedSelections? = null,
    coord: Coordinate,
    variables: List<SelectionSetVariable>,
    resolveFn: (suspend (ctx: Any) -> Any?),
    variablesProvider: VariablesProviderInfo?,
    resolverName: String?
) : AbstractFieldUnbatchedResolverStub<Ctx>(objectSelections, querySelections, coord, variables, resolveFn, variablesProvider, resolverName) {
    class Context(ctx: FieldExecutionContext<*, *, *, *>) :
        FieldExecutionContext<Object, Query, Arguments, CompositeOutput> by (ctx as FieldExecutionContext<Object, Query, Arguments, CompositeOutput>),
        InternalContext by (ctx as InternalContext)
}

class NodeUnbatchedResolverStub(
    val resolverFactory: NodeExecutionContextFactory,
    val resolverName: String?,
    val resolveFn: (suspend (ctx: Any) -> NodeObject),
) : NodeResolverBase<NodeObject> {
    @Suppress("UNUSED_PARAMETER")
    suspend fun resolve(
        self: Any,
        ctx: Any
    ) = resolveFn(ctx)

    val resolver: Provider<NodeResolverBase<*>> = Provider { this }
}

@Suppress("UNUSED_PARAMETER")
class NodeBatchResolverStub(
    val resolverFactory: NodeExecutionContextFactory,
    val resolverName: String?,
    val batchResolveFn: (suspend (ctxs: List<Any>) -> List<FieldValue<NodeObject>>),
) : NodeResolverBase<NodeObject> {
    suspend fun batchResolve(
        self: Any,
        ctxs: List<Any>
    ) = batchResolveFn(ctxs)

    val resolver: Provider<NodeResolverBase<*>> = Provider { this }
}

/**
 * Resolver stub for connection fields. The nested [Context] class implements
 * [ConnectionFieldExecutionContext] so that [FieldExecutionContextFactory] creates
 * a [ConnectionFieldExecutionContextImpl] instead of a plain [FieldExecutionContextImpl].
 */
class ConnectionFieldUnbatchedResolverStub<Ctx : BaseFieldExecutionContext<*, *, *>>(
    objectSelections: ParsedSelections? = null,
    querySelections: ParsedSelections? = null,
    coord: Coordinate,
    variables: List<SelectionSetVariable>,
    resolveFn: (suspend (ctx: Any) -> Any?),
    variablesProvider: VariablesProviderInfo?,
    resolverName: String?
) : AbstractFieldUnbatchedResolverStub<Ctx>(objectSelections, querySelections, coord, variables, resolveFn, variablesProvider, resolverName) {
    class Context(ctx: ConnectionFieldExecutionContext<Object, Query, ConnectionArguments, Connection<*, *>>) :
        ConnectionFieldExecutionContext<Object, Query, ConnectionArguments, Connection<*, *>> by ctx,
        InternalContext by (ctx as InternalContext)
}

class CheckerExecutorStub(
    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap(),
    private val executeFn: suspend (Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit,
    override val checkerMetadata: CheckerMetadata
) : CheckerExecutor {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext,
        checkerType: CheckerExecutor.CheckerType
    ): CheckerResult {
        try {
            executeFn(arguments, objectDataMap)
        } catch (e: Exception) {
            return MockCheckerErrorResult(e)
        }
        return CheckerResult.Success
    }
}

fun VariablesProviderInfo.Companion.const(vars: Map<String, Any?>): VariablesProviderInfo = typed<viaduct.tenant.runtime.FakeArguments>(vars.keys, { vars })

fun VariablesProviderInfo.Companion.untyped(
    vararg variables: String,
    fn: suspend (args: viaduct.tenant.runtime.FakeArguments) -> Map<String, Any?>
): VariablesProviderInfo = typed(variables.toSet(), fn)

fun <A : Arguments> VariablesProviderInfo.Companion.typed(
    vararg variables: String,
    fn: suspend (args: A) -> Map<String, Any?>
): VariablesProviderInfo = typed(variables.toSet(), fn)

fun <A : Arguments> VariablesProviderInfo.Companion.typed(
    variables: Set<String>,
    fn: suspend (args: A) -> Map<String, Any?>
): VariablesProviderInfo =
    VariablesProviderInfo(variables.toSet()) {
        VariablesProvider { context: VariablesProviderContext<A> -> fn(context.arguments) }
    }

/**
 * Extension function on Object that works for both FakeObject and real Objects.
 * If the receiver is a FakeObject, use FakeObject.get.
 * Otherwise, cast to ObjectBase and use its get method.
 */
suspend inline fun <reified T> Object.get(
    fieldName: String,
    alias: String? = null
): T {
    return when (this) {
        is viaduct.tenant.runtime.FakeObject -> this.get<T>(fieldName, alias)
        is viaduct.api.internal.ObjectBase -> this.get<T>(fieldName, T::class, alias)
        else -> throw IllegalStateException("Unexpected Object type: ${this::class}")
    }
}

/**
 * Extension function on Arguments that works for both FakeArguments and real Arguments.
 * If the receiver is a FakeArguments, use FakeArguments.get.
 * Otherwise, cast to InputLikeBase and access inputData directly.
 */
inline fun <reified T> Arguments.get(name: String): T {
    return when (this) {
        is viaduct.tenant.runtime.FakeArguments -> this.get<T>(name)
        is viaduct.api.internal.InputLikeBase -> {
            @Suppress("UNCHECKED_CAST")
            requireNotNull(this.inputData[name] as? T) { "$name is unset or null." }
        }

        else -> throw IllegalStateException("Unexpected Arguments type: ${this::class}")
    }
}

/**
 * Extension function on Arguments that works for both FakeArguments and real Arguments.
 * Returns null if the argument is not present or null.
 */
inline fun <reified T> Arguments.tryGet(name: String): T? {
    return when (this) {
        is viaduct.tenant.runtime.FakeArguments -> this.tryGet<T>(name)
        is viaduct.api.internal.InputLikeBase -> {
            @Suppress("UNCHECKED_CAST")
            this.inputData[name] as? T
        }

        else -> throw IllegalStateException("Unexpected Arguments type: ${this::class}")
    }
}
