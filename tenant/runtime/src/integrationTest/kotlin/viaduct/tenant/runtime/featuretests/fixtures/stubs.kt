package viaduct.tenant.runtime.featuretests.fixtures

import graphql.schema.GraphQLSchema
import javax.inject.Provider
import viaduct.api.FieldValue
import viaduct.api.VariablesProvider
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.ResolverBase
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSets
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.bootstrap.RequiredSelectionSetFactory
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.internal.VariablesProviderInfo

@Suppress("UNUSED_PARAMETER", "UNCHECKED_CAST")
class FieldUnbatchedResolverStub<Ctx : BaseFieldExecutionContext<*, *, *>>(
    val objectSelections: ParsedSelections? = null,
    val querySelections: ParsedSelections? = null,
    val coord: Coordinate,
    val variables: List<SelectionSetVariable>,
    val resolveFn: (suspend (ctx: Any) -> Any?),
    val variablesProvider: VariablesProviderInfo?,
    val resolverName: String?
) : ResolverBase<Any?> {
    // Nested Context class required by FieldExecutionContextFactory
    class Context(ctx: FieldExecutionContext<*, *, *, *>) :
        FieldExecutionContext<Object, Query, Arguments, CompositeOutput> by (ctx as FieldExecutionContext<Object, Query, Arguments, CompositeOutput>),
        InternalContext by (ctx as InternalContext)

    suspend fun resolve(
        self: Any,
        ctx: Any
    ) = resolveFn(ctx)

    val resolver: Provider<FieldUnbatchedResolverStub<Ctx>> = Provider { this }

    // Create the factory when schema is available (called from FeatureTestTenantModuleBootstrapper)
    fun resolverFactory(
        schema: ViaductSchema,
        reflectionLoader: ReflectionLoader
    ): FieldExecutionContextFactory =
        FieldExecutionContextFactory.of(
            resolverBaseClass = FieldUnbatchedResolverStub::class.java,
            globalIDCodec = GlobalIDCodecDefault,
            reflectionLoader = reflectionLoader,
            schema = schema,
            typeName = coord.first,
            fieldName = coord.second,
        )

    fun requiredSelectionSets(
        coord: Coordinate,
        schema: GraphQLSchema,
        reflectionLoader: ReflectionLoader
    ): RequiredSelectionSets {
        val variablesProviderContextFactory = resolverFactory(ViaductSchema(schema), reflectionLoader)

        val factory = RequiredSelectionSetFactory(GlobalIDCodecDefault, reflectionLoader)
        return factory.mkRequiredSelectionSets(
            variablesProvider = variablesProvider,
            objectSelections = objectSelections,
            querySelections = querySelections,
            variablesProviderContextFactory = variablesProviderContextFactory,
            variables = variables,
            attribution = resolverName?.let { ExecutionAttribution.fromResolver(it) },
        )
    }
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
