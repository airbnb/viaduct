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
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FieldCheckerDispatcherRegistry
import viaduct.engine.api.FieldResolverDispatcherRegistry
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.TypeCheckerDispatcherRegistry
import viaduct.engine.api.gj
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.observability.ExecutionObservabilityContext
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.findLocalContextForType
import viaduct.engine.runtime.updateCompositeLocalContext
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.Flags
import viaduct.utils.slf4j.logger

/**
 * Holds parameters used throughout the modern execution strategy.
 *
 * This class represents a position in the GraphQL execution tree, containing both
 * the immutable execution scope and the traversal-specific state that changes
 * as we navigate through the query.
 *
 * @property constants Immutable execution-wide constants shared across the entire execution
 * @property parentEngineResult Parent ObjectEngineResult for field execution (changes during traversal)
 * @property coercedVariables Coerced variables for the current execution context
 * @property queryPlan Current query plan being executed
 * @property localContext Local context for the current execution scope
 * @property source The source object for the current execution step
 * @property executionStepInfo Current position in the query execution tree
 * @property selectionSet Selection set for the current level of execution
 * @property errorAccumulator Errors collected at this level
 * @property parent Parent parameters in the traversal chain, if any
 * @property field Field currently being executed, if any
 * @property bypassChecksDuringCompletion If execution is in the context of an access check
 */
data class ExecutionParameters(
    val constants: Constants,
    val parentEngineResult: ObjectEngineResultImpl,
    val coercedVariables: CoercedVariables,
    val queryPlan: QueryPlan,
    val localContext: CompositeLocalContext,
    val source: Any?,
    val executionStepInfo: ExecutionStepInfo,
    val selectionSet: QueryPlan.SelectionSet,
    val errorAccumulator: ErrorAccumulator,
    val parent: ExecutionParameters? = null,
    val field: QueryPlan.CollectedField? = null,
    val bypassChecksDuringCompletion: Boolean = false
) {
    // Computed properties
    /** The ResultPath for the current level of execution */
    val path: ResultPath = executionStepInfo.path

    /** The ExecutionContext with the current local context applied */
    val executionContext: ExecutionContext = constants.executionContext.transform { it.localContext(localContext) }

    /** Convenient access to the GraphQL schema from constants */
    val graphQLSchema: GraphQLSchema = constants.executionContext.graphQLSchema

    /** Convenient access to instrumentation from constants */
    val instrumentation: ViaductModernGJInstrumentation = constants.instrumentation

    /** The root ObjectEngineResult for the entire request */
    val rootEngineResult: ObjectEngineResultImpl = constants.rootEngineResult

    /** The query ObjectEngineResult for query selections, if available */
    val queryEngineResult: ObjectEngineResultImpl = constants.queryEngineResult

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
        .parent(parent?.gjParameters)
        .field(this.field?.mergedField)
        .build()

    /**
     * Delegates to scope for launching coroutines on the root execution scope.
     *
     * @param block The suspend function to execute.
     */
    fun launchOnRootScope(block: suspend CoroutineScope.() -> Unit) = constants.launchOnRootScope(block)

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
            parentEngineResult = parentEngineResult,
            coercedVariables = coercedVariables,
            field = field,
            executionStepInfo = executionStepInfo,
            parent = this,
        )
    }

    /**
     * Creates ExecutionParameters for executing a child plan (Query or Object type).
     *
     * For Query-type child plans: Uses the queryEngineResult as the root with root
     * as source.
     * For Object-type child plans: Uses the current field's parent engine result
     * with current source.
     *
     * @param childPlan The child QueryPlan to execute
     * @param variables Resolved variables for the child plan
     * @return New ExecutionParameters configured for child plan execution
     */
    fun forChildPlan(
        childPlan: QueryPlan,
        variables: CoercedVariables
    ): ExecutionParameters {
        val objectType = childPlan.parentType as? GraphQLObjectType
            ?: throw IllegalArgumentException("Child plan must have a parent type of GraphQLObjectType")
        val isRootQueryQueryPlan = objectType == executionContext.graphQLSchema.queryType

        val newParentOER = if (isRootQueryQueryPlan) {
            // For root query plans, we use the query engine result
            constants.queryEngineResult
        } else {
            // For object plans, we use the current parent engine result
            parentEngineResult
        }

        val source = if (isRootQueryQueryPlan) {
            executionContext.getRoot()
        } else {
            // For object plans, we use the current source
            source
        }

        return forChildPlan(
            childPlan,
            variables,
            isRootQueryQueryPlan,
            objectType,
            newParentOER,
            source,
            executionStepInfo.parent, // for object plans, inherit the parent of the current field
        )
    }

    /**
     * Creates ExecutionParameters for executing a field type child plan (Query or Object type).
     * This is used when the child plan is for a field type.
     *
     * For Query-type child plans: Uses the queryEngineResult as the root with root
     * as source.
     * For Object-type child plans: Uses the field's engine result as the new parent OER
     * with field's original source to continue fetching field's type RSS.
     *
     * @param childPlan The child QueryPlan to execute
     * @param variables Resolved variables for the child plan
     * @param inputSource The source object for the field
     * @param engineResult The engine result for the field
     * @return new [ExecutionParameters] configured for field's type child plan execution
     */
    fun forFieldTypeChildPlan(
        childPlan: QueryPlan,
        variables: CoercedVariables,
        inputSource: Any?,
        engineResult: Any?,
    ): ExecutionParameters {
        val objectType = childPlan.parentType as? GraphQLObjectType
            ?: throw IllegalArgumentException("Child plan must have a parent type of GraphQLObjectType")
        val isRootQueryQueryPlan = objectType == executionContext.graphQLSchema.queryType

        val newParentOER = if (isRootQueryQueryPlan) {
            // For root query plans, we use the query engine result
            constants.queryEngineResult
        } else {
            // For object plans, we use the field's engine result as the containing OER
            // to continue fetching field's type RSS. Hence it is expected to be a non-null
            // ObjectEngineResultImpl.
            checkNotNull(engineResult as? ObjectEngineResultImpl) {
                "Expected ObjectEngineResultImpl but got $engineResult"
            }
        }

        val childPlanSource = if (isRootQueryQueryPlan) {
            executionContext.getRoot()
        } else {
            // For object plans, we use field's original source to continue fetching
            // field's type RSS.
            inputSource
        }

        return forChildPlan(
            childPlan,
            variables,
            isRootQueryQueryPlan,
            objectType,
            newParentOER,
            childPlanSource,
            executionStepInfo, // for field type object plans, we keep current execution step info as the parent
        )
    }

    /**
     * Internal helper to create ExecutionParameters for a child plan.
     *
     * @param childPlan The child QueryPlan to execute
     * @param variables Resolved variables for the child plan
     * @param isRootQueryQueryPlan True if the child plan is for the root query type
     * @param objectType The GraphQLObjectType that owns the child plan
     * @param newParentOER The ObjectEngineResult to use as parent for the child plan
     * @param source The source object for the child plan execution
     * @return New ExecutionParameters configured for child plan execution
     */
    private fun forChildPlan(
        childPlan: QueryPlan,
        variables: CoercedVariables,
        isRootQueryQueryPlan: Boolean,
        objectType: GraphQLObjectType,
        newParentOER: ObjectEngineResultImpl,
        source: Any?,
        parentFieldStepInfo: ExecutionStepInfo?,
    ): ExecutionParameters {
        // Build execution step info based on plan type
        val childExecutionStepInfo = if (isRootQueryQueryPlan) {
            // Query-type child plans get a completely fresh execution context
            ExecutionStepInfo.newExecutionStepInfo()
                .type(objectType)
                .path(ResultPath.rootPath())
                .parentInfo(null)
                .build()
        } else {
            checkNotNull(parentFieldStepInfo) {
                "Expected parent ExecutionStepInfo to be non-null for object-type child plan not on root query type"
            }
            // build new execution step info from the parent field step info and update type
            val esiBuilder = ExecutionStepInfo.newExecutionStepInfo(parentFieldStepInfo).type(objectType)
            // if the field isn't null, update it
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
                                    .selectionSet(childPlan.astSelectionSet)
                                    .build()
                            )
                            .build()
                    } else {
                        childPlan.astSelectionSet
                    }

                // update each field in the merged field to have the child plan's selection set
                val updatedFields = parentMergedField.fields.map { field ->
                    field.transform { spec ->
                        spec.selectionSet(updatedSelectionSet)
                    }
                }
                // build new merged field with updated fields
                val updatedMergedField = MergedField
                    .newMergedField(updatedFields)
                    .addDeferredExecutions(parentMergedField.deferredExecutions)
                    .build()
                esiBuilder.field(updatedMergedField)
            }
            esiBuilder.build()
        }

        val localContext = if (isRootQueryQueryPlan) {
            // For root query plans, we use the root local context
            executionContext.getLocalContext()
        } else {
            // For object plans, we use the current local context
            localContext
        }.addOrUpdate(
            ExecutionObservabilityContext(
                attribution = childPlan.attribution
            )
        )

        return copy(
            coercedVariables = variables,
            queryPlan = childPlan,
            selectionSet = childPlan.selectionSet,
            parent = this,
            errorAccumulator = ErrorAccumulator(),
            executionStepInfo = childExecutionStepInfo,
            parentEngineResult = newParentOER,
            localContext = localContext,
            source = source,
        )
    }

    /**
     * Creates ExecutionParameters for traversing into an object's selections.
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
    ): ExecutionParameters {
        return copy(
            parentEngineResult = engineResult, // Update parent to be the current object we're traversing into
            coercedVariables = coercedVariables,
            // ExecutionStepInfo.type is initially set to an abstract type like Node
            // It can be refined during execution as abstract types become resolved
            executionStepInfo = executionStepInfo.changeTypeWithPreservedNonNull(engineResult.graphQLObjectType),
            localContext = localContext,
            source = source,
            selectionSet = checkNotNull(field.selectionSet) { "Expected selection set to be non-null." },
        )
    }

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
            private val requiredSelectionSetRegistry: RequiredSelectionSetRegistry,
            private val fieldCheckerDispatcherRegistry: FieldCheckerDispatcherRegistry,
            private val typeCheckerDispatcherRegistry: TypeCheckerDispatcherRegistry,
            private val flagManager: FlagManager,
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
                executionContext: ExecutionContext,
                parameters: ExecutionStrategyParameters,
                rootEngineResult: ObjectEngineResultImpl,
                queryEngineResult: ObjectEngineResultImpl,
                supervisorScopeFactory: (CoroutineContext) -> CoroutineScope,
            ): ExecutionParameters {
                val engineExecutionContext = executionContext.findLocalContextForType<EngineExecutionContextImpl>()
                val planAttribution = ExecutionAttribution.fromOperation(executionContext.operationDefinition.name)

                // Build the query plan
                val (queryPlan, duration) = measureTimedValue {
                    QueryPlan.build(
                        QueryPlan.Parameters(
                            executionContext.executionInput.query,
                            engineExecutionContext.activeSchema,
                            requiredSelectionSetRegistry,
                            engineExecutionContext.executeAccessChecksInModstrat,
                            fieldResolverDispatcherRegistry = engineExecutionContext.dispatcherRegistry
                        ),
                        executionContext.document,
                        executionContext.executionInput.operationName
                            ?.takeIf(String::isNotEmpty)
                            ?.let(DocumentKey::Operation),
                        useCache = !flagManager.isEnabled(Flags.DISABLE_QUERY_PLAN_CACHE),
                        attribution = planAttribution
                    )
                }
                log.debug("Built QueryPlan in $duration")

                // Add the execution context with observability context for this query
                val localContext = executionContext.updateCompositeLocalContext<ExecutionObservabilityContext> {
                    ExecutionObservabilityContext(
                        attribution = planAttribution
                    )
                }

                // Create the execution scope with all execution-wide dependencies
                val constants = Constants(
                    executionContext = executionContext,
                    rootEngineResult = rootEngineResult,
                    queryEngineResult = queryEngineResult,
                    supervisorScopeFactory = supervisorScopeFactory,
                    rootCoroutineContext = coroutineContext,
                    requiredSelectionSetRegistry = requiredSelectionSetRegistry,
                    rawSelectionSetFactory = engineExecutionContext.rawSelectionSetFactory,
                    fieldCheckerDispatcherRegistry = fieldCheckerDispatcherRegistry,
                    typeCheckerDispatcherRegistry = typeCheckerDispatcherRegistry,
                    fieldResolverDispatcherRegistry = engineExecutionContext.dispatcherRegistry,
                )

                // Create and return root ExecutionParameters
                return ExecutionParameters(
                    constants = constants,
                    parentEngineResult = rootEngineResult, // Initially, parent is the same as root
                    coercedVariables = executionContext.coercedVariables,
                    queryPlan = queryPlan,
                    source = executionContext.getRoot(),
                    localContext = localContext,
                    executionStepInfo = parameters.executionStepInfo,
                    selectionSet = queryPlan.selectionSet,
                    errorAccumulator = ErrorAccumulator(),
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
     * @property queryEngineResult Query ObjectEngineResult for query selections
     * @property supervisorScopeFactory Coroutine scope factory for the entire execution. Creates a CoroutineScope supervised by the execution.
     * @property rootCoroutineContext Root coroutine context for async operations
     * @property requiredSelectionSetRegistry Registry for loading field data dependencies
     * @property rawSelectionSetFactory Factory for creating raw selection sets
     * @property fieldCheckerDispatcherRegistry Registry for field-level access checks
     * @property typeCheckerDispatcherRegistry Registry for type-level access checks
     * @property fieldResolverDispatcherRegistry Registry for field resolver dispatchers
     */
    data class Constants(
        val executionContext: ExecutionContext,
        val rootEngineResult: ObjectEngineResultImpl,
        val queryEngineResult: ObjectEngineResultImpl,
        val supervisorScopeFactory: (CoroutineContext) -> CoroutineScope,
        val rootCoroutineContext: CoroutineContext,
        val requiredSelectionSetRegistry: RequiredSelectionSetRegistry,
        val rawSelectionSetFactory: RawSelectionSet.Factory,
        val fieldCheckerDispatcherRegistry: FieldCheckerDispatcherRegistry,
        val typeCheckerDispatcherRegistry: TypeCheckerDispatcherRegistry,
        val fieldResolverDispatcherRegistry: FieldResolverDispatcherRegistry,
    ) {
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
        val instrumentation: ViaductModernGJInstrumentation
            get() = if (executionContext.instrumentation !is ViaductModernGJInstrumentation) {
                ViaductModernGJInstrumentation.fromStandardInstrumentation(executionContext.instrumentation)
            } else {
                executionContext.instrumentation as ViaductModernGJInstrumentation
            }
    }
}
