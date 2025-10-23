package viaduct.tenant.runtime.featuretests.fixtures

import graphql.schema.GraphQLSchema
import javax.inject.Provider
import viaduct.api.FieldValue
import viaduct.api.VariablesProvider
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.VariablesProviderContext
import viaduct.api.internal.NodeResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.ResolverBase
import viaduct.api.types.Arguments
import viaduct.api.types.NodeObject
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RequiredSelectionSets
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.tenant.runtime.bootstrap.RequiredSelectionSetFactory
import viaduct.tenant.runtime.context.factory.ArgumentsArgs
import viaduct.tenant.runtime.context.factory.Factory
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context2.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.globalid.GlobalIDCodecImpl
import viaduct.tenant.runtime.internal.VariablesProviderInfo

@Suppress("UNUSED_PARAMETER")
class FieldUnbatchedResolverStub<Ctx : FieldExecutionContext<*, *, *, *>>(
    val objectSelections: ParsedSelections? = null,
    val querySelections: ParsedSelections? = null,
    val resolverFactory: FieldExecutionContextFactory,
    val argumentsFactory: Factory<ArgumentsArgs, Arguments>,
    val variables: List<SelectionSetVariable>,
    val resolveFn: (suspend (ctx: Any) -> Any?),
    val variablesProvider: VariablesProviderInfo?,
    val resolverName: String?
) : ResolverBase<Any?> {
    suspend fun resolve(
        self: Any,
        ctx: Any
    ) = resolveFn(ctx)

    val resolver: Provider<FieldUnbatchedResolverStub<Ctx>> = Provider { this }

    fun requiredSelectionSets(
        coord: Coordinate,
        schema: GraphQLSchema,
        reflectionLoader: ReflectionLoader
    ): RequiredSelectionSets {
        val factory = RequiredSelectionSetFactory(GlobalIDCodecImpl(reflectionLoader), reflectionLoader)
        return factory.mkRequiredSelectionSets(
            variablesProvider = variablesProvider,
            objectSelections = objectSelections,
            querySelections = querySelections,
            argumentsFactory = argumentsFactory,
            variables = variables,
            attribution = resolverName?.let { ExecutionAttribution.fromResolver(it) },
        )
    }
}

class NodeUnbatchedResolverStub(
    val resolverFactory: NodeExecutionContextFactory,
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
    private val executeFn: suspend (Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit
) : CheckerExecutor {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
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
