package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.MergedField
import graphql.execution.MergedSelectionSet
import graphql.execution.NonNullableFieldValidator
import graphql.execution.ResultPath
import graphql.language.InlineFragment as GJInlineFragment
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName as GJTypeName
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.GraphQLTypeUtil
import java.util.function.Supplier
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import viaduct.engine.api.Caller
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.gj
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.runtime.EngineExecutionContextExtensions.asImpl
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.setExecutionHandle
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.observability.ExecutionObservabilityContext
import viaduct.utils.slf4j.logger

/** Describes the result/source boundary against which a child [QueryPlan] should execute. */
sealed interface ChildQueryPlanTarget {
    /**
     * Execute a child plan against the object that owns the current field.
     *
     * The child QueryPlan reuses the current object's result and source.
     */
    object CurrentObjectResult : ChildQueryPlanTarget

    /** Execute a child QueryPlan against the active query result. */
    object CurrentQueryResult : ChildQueryPlanTarget

    /**
     * Execute a child QueryPlan against an explicitly supplied object result.
     *
     * The supplied result becomes the current object result while the caller's source and
     * surrounding query context are preserved.
     */
    @JvmInline
    value class ExplicitObjectResult(val result: ObjectEngineResultImpl) : ChildQueryPlanTarget

    /** Execute a child QueryPlan for a root selection set in an isolated result context. */
    data class IsolatedRootResults(
        val rootResult: ObjectEngineResultImpl,
        val queryResult: ObjectEngineResultImpl,
    ) : ChildQueryPlanTarget

    /**
     * Execute a child plan against the object value returned by the current field.
     *
     * The current parameters still point at the object that owns the field, so the
     * returned object's result and source must be supplied explicitly.
     */
    data class ResolvedFieldObjectResult(
        val objectResult: ObjectEngineResultImpl,
        val source: Any?,
    ) : ChildQueryPlanTarget
}

/**
 * Describes how an [ExecutionParameters] scope relates to its active [QueryPlan].
 *
 * [Root] begins execution of the request's initial QueryPlan. [Field] and [ObjectTraversal]
 * continue within the same QueryPlan, while [ChildQueryPlan] begins a scope with a separate
 * child QueryPlan.
 */
sealed interface ExecutionOrigin {
    /** The initial execution scope for a request's QueryPlan. */
    object Root : ExecutionOrigin

    /** Field execution derived from [parameters] without changing the active QueryPlan. */
    @JvmInline
    value class Field(val parameters: ExecutionParameters) : ExecutionOrigin

    /** Object traversal derived from [parameters] without changing the active QueryPlan. */
    @JvmInline
    value class ObjectTraversal(val parameters: ExecutionParameters) : ExecutionOrigin

    /**
     * Execution derived from [parameters] with a child QueryPlan as the active plan.
     *
     * [target] identifies the result boundary against which the child QueryPlan executes.
     */
    data class ChildQueryPlan(
        val parameters: ExecutionParameters,
        val target: ChildQueryPlanTarget,
    ) : ExecutionOrigin
}

/**
 * Holds parameters used throughout the modern execution strategy.
 *
 * This class represents a position in the GraphQL execution tree, containing both
 * the immutable execution scope and the traversal-specific state that changes
 * as we navigate through the query.
 *
 * ## EngineExecutionContext Handling
 *
 * Each `ExecutionParameters` instance owns its own [EngineExecutionContext] copy, created in `init`.
 * This ensures that modifications to one parameters instance don't affect others.
 *
 * The [EngineExecutionContext.executionHandle] property creates a bidirectional link between
 * the EEC and its owning ExecutionParameters. This is set eagerly in `init`, ensuring that
 * any subsequent [EngineExecutionContext.copy] calls preserve the correct handle.
 *
 * To create a derived EEC with modified field scope or DFE, use
 * [viaduct.engine.runtime.EngineExecutionContextExtensions.copy] on [engineExecutionContext].
 * The copy will automatically preserve the handle pointing to this ExecutionParameters.
 *
 * @property constants Immutable execution-wide constants shared across the entire execution
 * @property currentObjectEngineResult ObjectEngineResult for the object currently being resolved
 * @property coercedVariables Coerced variables for the current execution context
 * @property queryPlan Current query plan being executed
 * @property queryPlanIndex Index over currently materialized query plans for RSS lookup during subquery execution
 * @property localContext Local context for the current execution scope
 * @property source The source object for the current execution step
 * @property executionStepInfo Current position in the query execution tree
 * @property selectionSet Selection set for the current level of execution
 * @property errorAccumulator Errors collected at this level
 * @property executionOrigin How this execution scope was derived
 * @property field Field currently being executed, if any
 * @property bypassChecksDuringCompletion If execution is in the context of an access check
 * @property resolutionPolicy The resolution policy to use for this execution step
 * @property matBatchDepth The number of field Mat re-runs leading to this execution. Each re-run
 *   increments the depth so it uses a separate batch from the call waiting for it. Mats started
 *   together keep the same depth and can still share a batch.
 * @property isShadowFieldExecution Whether this scope belongs to the temporary shadow execution
 *   subtree
 */
data class ExecutionParameters(
    @Suppress("ConstructorParameterNaming")
    private var _engineExecutionContext: EngineExecutionContext,
    val constants: Constants,
    val currentObjectEngineResult: ObjectEngineResultImpl,
    val queryEngineResult: ObjectEngineResultImpl,
    val coercedVariables: CoercedVariables,
    val queryPlan: QueryPlan,
    val queryPlanIndex: QueryPlanIndex,
    val localContext: CompositeLocalContext,
    val source: Any?,
    val executionStepInfo: ExecutionStepInfo,
    val selectionSet: QueryPlan.SelectionSet,
    val errorAccumulator: ErrorAccumulator,
    val executionOrigin: ExecutionOrigin = ExecutionOrigin.Root,
    val field: QueryPlan.CollectedField? = null,
    val bypassChecksDuringCompletion: Boolean = false,
    val resolutionPolicy: ResolutionPolicy = ResolutionPolicy.STANDARD,
    val attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
    val matBatchDepth: Int = 0,
    val isShadowFieldExecution: Boolean = false,
    @Suppress("ConstructorParameterNaming")
    private val _caller: Caller? = null,
) : EngineExecutionContext.ExecutionHandle {
    // Each ExecutionParameters gets its own EEC copy to prevent cross-contamination
    // between different execution contexts (e.g., parent vs child field resolution).
    // The field scope is bound here so the EEC always reflects this parameter's plan
    // variables/fragments. The handle is set eagerly to ensure eec.copy() preserves
    // the correct handle.
    init {
        _engineExecutionContext = _engineExecutionContext.copy(
            fieldScopeSupplier = fieldExecutionScopeSupplier(),
            matBatchDepth = matBatchDepth,
        )
        _engineExecutionContext.setExecutionHandle(this)
    }

    /** The ResultPath for the current level of execution */
    val path: ResultPath = executionStepInfo.path

    /** The ExecutionContext with the current local context applied */
    val executionContextWithLocalContext: ExecutionContext by lazy(LazyThreadSafetyMode.PUBLICATION) {
        constants.executionContext.transform {
            it.localContext(localContext.addOrUpdate(ExecutionObservabilityContext(attribution = attribution)))
        }
    }

    val executionContext: ExecutionContext = constants.executionContext

    /** Convenient access to the GraphQL schema from constants */
    val graphQLSchema: GraphQLSchema = constants.executionContext.graphQLSchema

    /** Convenient access to instrumentation from constants */
    val instrumentation: ViaductModernGJInstrumentation = constants.instrumentation

    /** The root ObjectEngineResult for the entire request */
    val rootEngineResult: ObjectEngineResultImpl = constants.rootEngineResult

    val gjParameters: ExecutionStrategyParameters = ExecutionStrategyParameters.newParameters()
        // graphql-java requires a merged selection set, though our execution strategy doesn't use it.
        // provide a placeholder value
        .fields(emptyMergedSelectionSet)
        .source(source) // in some cases this should be the resolved one in currentEngineResult
        // nonNullFieldValidator is required but not used in modstrat
        // see [viaduct.engine.runtime.execution.NonNullableFieldValidator]
        .localContext(localContext)
        .nonNullFieldValidator(NonNullableFieldValidator(executionContext))
        .executionStepInfo(executionStepInfo)
        .path(path)
        .field(this.field?.mergedField)
        .build()

    /**
     * Returns the [EngineExecutionContext] for this execution parameters instance.
     *
     * The handle is set eagerly in the init block, so this is a simple accessor.
     */
    val engineExecutionContext: EngineExecutionContext
        get() = _engineExecutionContext

    /**
     * Returns a copy that attributes execution to [caller], not to the resolver found through
     * [executionOrigin].
     *
     * Returns this instance when [caller] is null.
     */
    fun withCaller(caller: Caller?): ExecutionParameters = if (caller == null) this else copy(_caller = caller)

    /**
     * The field resolver for the field that these parameters execute. Null when that field has no
     * resolver.
     *
     * Uses the coordinate that the field's metadata records as the resolving coordinate. Falls back
     * to the field's own type and name. A plain data field has no resolver, so it returns null.
     */
    private val currentFieldResolverCaller: Caller?
        get() {
            val currentField = this@ExecutionParameters.field ?: return null
            val coordinate =
                currentField.collectedFieldMetadata?.resolvedByCoordinate
                    ?: (executionStepInfo.objectType.name to currentField.mergedField.name)
            val resolver =
                engineExecutionContext
                    .asImpl()
                    .dispatcherRegistry
                    .getFieldResolverDispatcher(coordinate.first, coordinate.second)
                    ?: return null
            return Caller(
                tenantName = resolver.resolverMetadata.tenantMetadata?.name,
                typeName = coordinate.first,
                fieldName = coordinate.second,
            )
        }

    /**
     * The node resolver that produced the object being traversed. Null unless this traversal
     * entered an object fetched by a node resolver.
     *
     * A node resolver resolves a whole type, so it has no field name. The field that created the
     * node reference is not the caller: it only supplied an id, and the node resolver is what
     * produced the data these selections read.
     */
    private val currentNodeResolverCaller: Caller?
        get() {
            val node = source as? NodeEngineObjectData ?: return null
            val resolver = engineExecutionContext
                .asImpl()
                .dispatcherRegistry
                .getNodeResolverDispatcher(node.type.name)
                ?: return null
            return Caller(
                tenantName = resolver.resolverMetadata.tenantMetadata?.name,
                typeName = node.type.name,
                fieldName = null,
            )
        }

    /**
     * The resolver that caused this execution to run.
     *
     * Returns [_caller] when it is set. If it is not set, searches outward through
     * [executionOrigin] for the nearest resolver. Returns null at the root of a request.
     */
    internal val caller: Caller?
        get() =
            _caller ?: when (val origin = executionOrigin) {
                ExecutionOrigin.Root -> null
                is ExecutionOrigin.Field -> origin.parameters.caller
                is ExecutionOrigin.ObjectTraversal ->
                    currentNodeResolverCaller
                        ?: origin.parameters.caller
                        ?: origin.parameters.currentFieldResolverCaller
                is ExecutionOrigin.ChildQueryPlan ->
                    origin.parameters.caller ?: resolverCaller(origin.parameters)
            }

    /**
     * The field resolver that owns the child query plan these parameters execute.
     *
     * Returns null unless the plan belongs to a resolver. Policy checks and variables resolvers
     * also run as child plans, and they are not callers.
     *
     * @param parent the parameters that started this child plan
     */
    private fun resolverCaller(parent: ExecutionParameters): Caller? {
        if (queryPlan.attribution?.type != ExecutionAttribution.Type.RESOLVER) {
            return null
        }
        return parent.currentFieldResolverCaller
    }

    private fun fieldExecutionScopeSupplier(): Supplier<EngineExecutionContext.FieldExecutionScope> =
        Supplier {
            EngineExecutionContextImpl.FieldExecutionScopeImpl(
                // Reconstruct selections with the same fragment namespace as the plan's AST.
                // RSS fragments may be pruned from the executable plan or collide by name with
                // fragments from the client operation.
                fragments = queryPlan.fragments.source,
                variables = coercedVariables.toMap(),
                resolutionPolicy = resolutionPolicy,
                attribution = queryPlan.attribution ?: ExecutionAttribution.DEFAULT,
                caller = caller,
            )
        }

    /** replace [queryPlanIndex] with the provided value */
    fun withQueryPlanIndex(newQueryPlanIndex: QueryPlanIndex): ExecutionParameters =
        if (newQueryPlanIndex === queryPlanIndex) {
            this
        } else {
            copy(queryPlanIndex = newQueryPlanIndex)
        }

    /**
     * Delegates to scope for launching coroutines on the root execution scope.
     *
     * @param block The suspend function to execute.
     */
    fun launchOnRootScope(block: suspend CoroutineScope.() -> Unit) = constants.launchOnRootScope(block)

    /**
     * Returns the nearest object execution scope above this scope.
     *
     * This intentionally excludes the object that contains the current field. For example,
     * while executing a field on `Query.viewer.address`, the nearest object ancestor of
     * `address` is `viewer`, not `address` itself.
     *
     * An [ExecutionOrigin.Field] stays on the same object, so it does not add an ancestor.
     * An [ExecutionOrigin.ObjectTraversal] enters the object returned by a field. Most
     * [ExecutionOrigin.ChildQueryPlan] origins do not change object ancestry, but a non-Query
     * [ChildQueryPlanTarget.ResolvedFieldObjectResult] enters the object returned by the current
     * field and therefore has the field's containing object as its nearest ancestor.
     */
    fun nearestObjectAncestor(): ExecutionParameters? =
        when (val origin = executionOrigin) {
            // Executing a field does not enter a new object. Continue from the object
            // containing the field while still excluding that object from the result.
            is ExecutionOrigin.Field -> origin.parameters.nearestObjectAncestor()

            // The current object was produced by a field, so return the object that owns it.
            is ExecutionOrigin.ObjectTraversal ->
                (origin.parameters.executionOrigin as? ExecutionOrigin.Field)?.parameters
                    ?: error("Expected object traversal to start from field execution parameters")

            is ExecutionOrigin.ChildQueryPlan ->
                if (origin.target is ChildQueryPlanTarget.ResolvedFieldObjectResult &&
                    queryPlan.parentType != graphQLSchema.queryType
                ) {
                    // The child plan executes against the object returned by the current field.
                    // Its nearest ancestor is the object that owns that field.
                    (origin.parameters.executionOrigin as? ExecutionOrigin.Field)?.parameters
                        ?: error("Expected resolved field child plan to start from field execution parameters")
                } else {
                    // Replacing the active QueryPlan does not change the runtime object ancestry.
                    origin.parameters.nearestObjectAncestor()
                }

            // The request root has no object above it.
            ExecutionOrigin.Root -> null
        }

    /**
     * Creates ExecutionParameters for executing a specific field.
     *
     * @param objectType The GraphQLObjectType that owns the field definition
     * @param field The CollectedField to be executed
     * @return New ExecutionParameters configured for field execution
     */
    fun forField(
        objectType: GraphQLObjectType,
        field: QueryPlan.CollectedField
    ): ExecutionParameters {
        val coord = objectType.name to field.mergedField.name
        val fieldDef = executionContext.graphQLSchema.getFieldDefinition(coord.gj)
        val path = path.segment(field.responseKey)
        val mergedField = field.mergedField
        val executionStepInfo = FieldExecutionHelpers.createExecutionStepInfo(
            graphQLSchema.codeRegistry,
            executionContext,
            coercedVariables,
            mergedField,
            path,
            executionStepInfo,
            fieldDef,
            objectType,
        )
        return copy(
            currentObjectEngineResult = currentObjectEngineResult,
            coercedVariables = coercedVariables,
            field = field,
            executionStepInfo = executionStepInfo,
            executionOrigin = ExecutionOrigin.Field(this),
            resolutionPolicy = resolutionPolicy,
        )
    }

    /**
     * Forks this field's mutable execution state for a shadow rerun while preserving request inputs
     * and source. Shadow traversal through `@parent` is rejected because ancestor state is not
     * forked.
     */
    internal fun forShadowFieldExecution(coroutineContext: CoroutineContext): ExecutionParameters {
        check(field != null) {
            "Shadow field execution requires execution parameters for a current field"
        }
        check(!isShadowFieldExecution) {
            "Shadow field execution cannot be nested"
        }

        val shadowRootResult = ObjectEngineResultImpl.newForType(rootEngineResult.type)
        val shadowQueryResult =
            if (queryEngineResult === rootEngineResult) {
                shadowRootResult
            } else {
                ObjectEngineResultImpl.newForType(queryEngineResult.type)
            }
        val shadowCurrentObjectResult = when (currentObjectEngineResult) {
            rootEngineResult -> shadowRootResult
            queryEngineResult -> shadowQueryResult
            else -> ObjectEngineResultImpl.newForType(currentObjectEngineResult.type)
        }
        val shadowContext = engineExecutionContext.asImpl().forkForShadowExecution()

        return copy(
            _engineExecutionContext = shadowContext,
            constants = constants.copy(
                rootEngineResult = shadowRootResult,
                supervisorScopeFactory = { CoroutineScope(it) },
                rootCoroutineContext = coroutineContext,
            ),
            currentObjectEngineResult = shadowCurrentObjectResult,
            queryEngineResult = shadowQueryResult,
            localContext = localContext.addOrUpdate(shadowContext),
            errorAccumulator = ErrorAccumulator(),
            isShadowFieldExecution = true,
            matBatchDepth = 0,
        )
    }

    /** Returns the semantic target for a normal object- or Query-rooted child [QueryPlan]. */
    fun targetForChildPlan(childPlan: QueryPlan): ChildQueryPlanTarget {
        val objectType = childPlan.parentType as? GraphQLObjectType
            ?: throw IllegalArgumentException("Child QueryPlan must have a parent type of GraphQLObjectType")
        return if (objectType == executionContext.graphQLSchema.queryType) {
            ChildQueryPlanTarget.CurrentQueryResult
        } else {
            ChildQueryPlanTarget.CurrentObjectResult
        }
    }

    /**
     * Resolves the object results used while evaluating variables before the child [QueryPlan] runs.
     *
     * Query-typed child QueryPlans execute against the query root, but variables on query
     * selections may have object RSS that should read from the caller's current object.
     */
    internal fun childPlanVariableResolutionTarget(
        childPlan: QueryPlan,
        target: ChildQueryPlanTarget,
    ): ChildPlanExecutionTarget {
        val executionTarget = childPlanExecutionTarget(childPlan, target)
        val variableCurrentObjectEngineResult = when (target) {
            is ChildQueryPlanTarget.ResolvedFieldObjectResult -> target.objectResult
            is ChildQueryPlanTarget.IsolatedRootResults -> executionTarget.currentObjectEngineResult
            else -> currentObjectEngineResult
        }
        return executionTarget.copy(currentObjectEngineResult = variableCurrentObjectEngineResult)
    }

    /** Creates execution parameters that replace the active [queryPlan] with [childPlan]. */
    fun forChildPlan(
        childPlan: QueryPlan,
        variables: CoercedVariables,
        target: ChildQueryPlanTarget,
    ): ExecutionParameters {
        val executionTarget = childPlanExecutionTarget(childPlan, target)
        val objectType = executionTarget.objectType
        val isRootQueryQueryPlan = executionTarget.isRootQueryQueryPlan

        val newConstants = when (target) {
            is ChildQueryPlanTarget.IsolatedRootResults ->
                constants.copy(
                    rootEngineResult = target.rootResult,
                )
            else -> constants
        }

        val childSource = if (isRootQueryQueryPlan) {
            executionContext.getRoot()
        } else {
            when (target) {
                is ChildQueryPlanTarget.ResolvedFieldObjectResult -> target.source
                else -> source
            }
        }

        val parentStepInfo = when (target) {
            is ChildQueryPlanTarget.ResolvedFieldObjectResult -> executionStepInfo
            else -> executionStepInfo.parent
        }

        return buildChildParameters(
            childPlan = childPlan,
            variables = variables,
            isRootQueryQueryPlan = isRootQueryQueryPlan,
            objectType = objectType,
            newCurrentObjectEngineResult = executionTarget.currentObjectEngineResult,
            newQueryEngineResult = executionTarget.queryEngineResult,
            source = childSource,
            parentFieldStepInfo = parentStepInfo,
            newConstants = newConstants,
            target = target,
        )
    }

    private fun childPlanExecutionTarget(
        childPlan: QueryPlan,
        target: ChildQueryPlanTarget,
    ): ChildPlanExecutionTarget {
        val objectType = childPlan.parentType as? GraphQLObjectType
            ?: throw IllegalArgumentException("Child QueryPlan must have a parent type of GraphQLObjectType")
        val isRootQueryQueryPlan = objectType == executionContext.graphQLSchema.queryType

        val childQueryEngineResult = when (target) {
            is ChildQueryPlanTarget.IsolatedRootResults -> target.queryResult
            else -> queryEngineResult
        }

        val childCurrentObjectEngineResult = when (target) {
            ChildQueryPlanTarget.CurrentObjectResult -> currentObjectEngineResult
            ChildQueryPlanTarget.CurrentQueryResult -> childQueryEngineResult
            is ChildQueryPlanTarget.ExplicitObjectResult -> target.result
            is ChildQueryPlanTarget.IsolatedRootResults ->
                if (isRootQueryQueryPlan) target.queryResult else target.rootResult
            is ChildQueryPlanTarget.ResolvedFieldObjectResult ->
                if (isRootQueryQueryPlan) childQueryEngineResult else target.objectResult
        }

        return ChildPlanExecutionTarget(
            objectType = objectType,
            isRootQueryQueryPlan = isRootQueryQueryPlan,
            currentObjectEngineResult = childCurrentObjectEngineResult,
            queryEngineResult = childQueryEngineResult,
        )
    }

    private fun buildChildParameters(
        childPlan: QueryPlan,
        variables: CoercedVariables,
        isRootQueryQueryPlan: Boolean,
        objectType: GraphQLObjectType,
        newCurrentObjectEngineResult: ObjectEngineResultImpl,
        newQueryEngineResult: ObjectEngineResultImpl,
        source: Any?,
        parentFieldStepInfo: ExecutionStepInfo?,
        newConstants: Constants,
        target: ChildQueryPlanTarget,
    ): ExecutionParameters {
        val childExecutionStepInfo = if (isRootQueryQueryPlan) {
            ExecutionStepInfo.newExecutionStepInfo()
                .type(objectType)
                .path(ResultPath.rootPath())
                .parentInfo(null)
                .build()
        } else {
            checkNotNull(parentFieldStepInfo) {
                "Expected parent ExecutionStepInfo to be non-null for object-type child QueryPlan not on root query type"
            }
            val esiBuilder = ExecutionStepInfo.newExecutionStepInfo(parentFieldStepInfo).type(objectType)
            if (parentFieldStepInfo.field != null) {
                val parentMergedField = parentFieldStepInfo.field
                val parentFieldType = parentFieldStepInfo.fieldDefinition.type?.let(GraphQLTypeUtil::unwrapAll)
                val requiresInlineFragment = parentFieldType !is GraphQLObjectType
                val updatedSelectionSet: GJSelectionSet =
                    if (requiresInlineFragment) {
                        GJSelectionSet
                            .newSelectionSet()
                            .selection(
                                GJInlineFragment
                                    .newInlineFragment()
                                    .typeCondition(GJTypeName(objectType.name))
                                    .selectionSet(childPlan.selectionSet.toAstSelectionSet())
                                    .build()
                            )
                            .build()
                    } else {
                        childPlan.selectionSet.toAstSelectionSet()
                    }

                val updatedFields = parentMergedField.fields.map { field ->
                    field.transform { spec ->
                        spec.selectionSet(updatedSelectionSet)
                    }
                }
                val updatedMergedField = MergedField
                    .newMergedField(updatedFields)
                    .addDeferredExecutions(parentMergedField.deferredExecutions)
                    .build()
                esiBuilder.field(updatedMergedField)
            }
            esiBuilder.build()
        }

        val localContext = if (isRootQueryQueryPlan) {
            val operationLocalContext = executionContext.getLocalContext<CompositeLocalContext>()
            if (isShadowFieldExecution) {
                operationLocalContext.addOrUpdate(
                    checkNotNull(localContext.get<EngineExecutionContextImpl>()) {
                        "Shadow field execution requires an EngineExecutionContext in local context"
                    }
                )
            } else {
                operationLocalContext
            }
        } else {
            localContext
        }

        val newIndex = if (childPlan.index !== queryPlanIndex) {
            queryPlanIndex + childPlan.index
        } else {
            queryPlanIndex
        }

        return copy(
            constants = newConstants,
            coercedVariables = variables,
            queryPlan = childPlan,
            queryPlanIndex = newIndex,
            selectionSet = childPlan.selectionSet,
            errorAccumulator = ErrorAccumulator(),
            executionStepInfo = childExecutionStepInfo,
            currentObjectEngineResult = newCurrentObjectEngineResult,
            queryEngineResult = newQueryEngineResult,
            localContext = localContext,
            source = source,
            // A child-plan root is an object scope, not an execution of the parent plan's field.
            // The originating field remains available through ExecutionOrigin.ChildQueryPlan.
            field = null,
            executionOrigin = ExecutionOrigin.ChildQueryPlan(this, target),
            attribution = childPlan.attribution,
        )
    }

    internal data class ChildPlanExecutionTarget(
        val objectType: GraphQLObjectType,
        val isRootQueryQueryPlan: Boolean,
        val currentObjectEngineResult: ObjectEngineResultImpl,
        val queryEngineResult: ObjectEngineResultImpl,
    )

    /**
     * Creates ExecutionParameters for normal traversal into a field's returned object.
     *
     * Parent fields use [forParentFieldTraversal] because they return an existing ancestor
     * rather than introducing a new child object.
     *
     * @param field The field containing the selection set to traverse
     * @param engineResult The ObjectEngineResult for the current object
     * @param localContext The local context for the current execution scope
     * @param source The source object for the current execution step
     * @return New ExecutionParameters configured for object traversal
     */
    fun forObjectTraversal(
        field: QueryPlan.CollectedField,
        engineResult: ObjectEngineResultImpl,
        localContext: CompositeLocalContext,
        source: Any?,
        resolutionPolicy: ResolutionPolicy = this.resolutionPolicy,
    ): ExecutionParameters {
        check(executionOrigin is ExecutionOrigin.Field) {
            "Expected object traversal to start from field execution parameters"
        }
        return copy(
            currentObjectEngineResult = engineResult,
            coercedVariables = coercedVariables,
            // ExecutionStepInfo.type is initially set to an abstract type like Node
            // It can be refined during execution as abstract types become resolved
            executionStepInfo = executionStepInfo.changeTypeWithPreservedNonNull(engineResult.type),
            localContext = localContext,
            source = source,
            selectionSet = checkNotNull(field.selectionSet) { "Expected selection set to be non-null." },
            executionOrigin = ExecutionOrigin.ObjectTraversal(this),
            resolutionPolicy = resolutionPolicy,
        )
    }

    /**
     * Creates ExecutionParameters for traversing into an object returned by a @parent field.
     *
     * Unlike normal object traversal, the returned object is already an existing ancestor
     * of the current object. Preserve that ancestor's own execution origin so nested
     * parent fields keep walking upward through the original object chain.
     */
    fun forParentFieldTraversal(
        field: QueryPlan.CollectedField,
        parentParameters: ExecutionParameters,
        localContext: CompositeLocalContext,
        resolutionPolicy: ResolutionPolicy = this.resolutionPolicy,
    ): ExecutionParameters =
        copy(
            currentObjectEngineResult = parentParameters.currentObjectEngineResult,
            executionStepInfo = executionStepInfo.changeTypeWithPreservedNonNull(parentParameters.currentObjectEngineResult.type),
            localContext = localContext,
            source = parentParameters.source,
            selectionSet = checkNotNull(field.selectionSet) { "Expected selection set to be non-null." },
            executionOrigin = parentParameters.executionOrigin,
            resolutionPolicy = resolutionPolicy,
        )

    /**
     * Factory for creating root [ExecutionParameters] instances.
     *
     * This factory is responsible for:
     * - Building the initial QueryPlan
     * - Creating the ExecutionScope with all execution-wide dependencies
     * - Constructing the root ExecutionParameters for query execution
     */
    class Factory
        @Inject
        constructor(
            private val queryPlanFactory: QueryPlanFactory,
        ) {
            companion object {
                private val log by logger()
            }

            /**
             * Creates root ExecutionParameters from the execution context and strategy parameters.
             *
             * @param executionContext The execution context for the GraphQL query
             * @param parameters The execution strategy parameters
             * @param rootEngineResult The root object engine result
             * @param queryEngineResult The query object engine result for query selections
             * @return A new instance of [ExecutionParameters] configured for root execution
             */
            @OptIn(ExperimentalTime::class)
            internal suspend fun fromExecutionStrategyContextAndParameters(
                engineExecutionContext: EngineExecutionContextImpl,
                executionContext: ExecutionContext,
                parameters: ExecutionStrategyParameters,
                rootEngineResult: ObjectEngineResultImpl,
                queryEngineResult: ObjectEngineResultImpl,
                supervisorScopeFactory: (CoroutineContext) -> CoroutineScope,
            ): ExecutionParameters {
                val planAttribution = ExecutionAttribution.fromOperation(executionContext.operationDefinition.name)

                // Build the query plan
                val (queryPlan, duration) = measureTimedValue {
                    queryPlanFactory.build(
                        QueryPlan.Parameters(
                            executionContext.executionInput.query,
                            engineExecutionContext.activeSchema,
                            engineExecutionContext.dispatcherRegistry,
                            engineExecutionContext.dispatcherRegistry
                        ),
                        executionContext.document,
                        executionContext.executionInput.operationName
                            ?.takeIf(String::isNotEmpty)
                            ?.let(DocumentKey::Operation),
                        attribution = planAttribution
                    )
                }
                log.debug("Built QueryPlan in $duration")

                val currentCoroutineContext = currentCoroutineContext()

                // Create the execution scope with all execution-wide dependencies
                val constants = Constants(
                    executionContext = executionContext,
                    rootEngineResult = rootEngineResult,
                    supervisorScopeFactory = supervisorScopeFactory,
                    rootCoroutineContext = currentCoroutineContext,
                )

                return ExecutionParameters(
                    _engineExecutionContext = engineExecutionContext,
                    constants = constants,
                    currentObjectEngineResult = rootEngineResult, // Initially, the current object is the root
                    queryEngineResult = queryEngineResult,
                    coercedVariables = executionContext.coercedVariables,
                    queryPlan = queryPlan,
                    queryPlanIndex = queryPlan.index,
                    source = executionContext.getRoot(),
                    localContext = executionContext.getLocalContext(),
                    executionStepInfo = parameters.executionStepInfo,
                    selectionSet = queryPlan.selectionSet,
                    errorAccumulator = ErrorAccumulator(),
                    attribution = planAttribution,
                )
            }
        }

    companion object {
        private val emptyMergedSelectionSet = MergedSelectionSet.newMergedSelectionSet().build()
    }

    /**
     * Immutable object containing execution-wide constants that remain unchanged throughout
     * the entire GraphQL query execution.
     *
     * This class encapsulates all the dependencies and context that are shared across
     * the entire execution tree, separating them from the traversal-specific state
     * in [ExecutionParameters].
     *
     * @property executionContext Base GraphQL execution context from graphql-java
     * @property rootEngineResult Root ObjectEngineResult for the entire request
     * @property supervisorScopeFactory Coroutine scope factory for the entire execution. Creates a CoroutineScope supervised by the execution.
     * @property rootCoroutineContext Root coroutine context for async operations
     * @property collectCache Cache for collected fields during execution
     */
    data class Constants(
        val executionContext: ExecutionContext,
        val rootEngineResult: ObjectEngineResultImpl,
        val supervisorScopeFactory: (CoroutineContext) -> CoroutineScope,
        val rootCoroutineContext: CoroutineContext,
    ) {
        internal val collectCache: CollectCache = CollectCache()

        /**
         * Launches a coroutine on the root execution scope.
         * This ensures all async operations are properly scoped to the execution lifetime.
         *
         * @param block The suspend function to execute
         */
        fun launchOnRootScope(block: suspend CoroutineScope.() -> Unit) =
            supervisorScopeFactory(rootCoroutineContext).launch {
                block(this)
            }

        /**
         * The instrumentation instance from the execution context.
         * Automatically wraps standard instrumentation in ViaductModernGJInstrumentation if needed.
         */
        val instrumentation: ViaductModernGJInstrumentation =
            if (executionContext.instrumentation !is ViaductModernGJInstrumentation) {
                ViaductModernGJInstrumentation.fromStandardInstrumentation(executionContext.instrumentation)
            } else {
                executionContext.instrumentation as ViaductModernGJInstrumentation
            }
    }
}
