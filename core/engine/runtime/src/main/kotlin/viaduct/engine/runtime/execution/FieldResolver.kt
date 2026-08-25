@file:Suppress("DEPRECATION") // CoroutineInterop retained for Airbnb

package viaduct.engine.runtime.execution

import graphql.execution.DataFetcherResult
import graphql.execution.FetchedValue
import graphql.execution.ResolveType
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.SimpleInstrumentationContext.nonNullCtx
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLTypeUtil
import graphql.util.FpKit
import java.util.function.Supplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeEngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.instrumentation.InstrumentNodeFetchingParameters
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.api.spi.ShadowFieldExecutionComparison
import viaduct.engine.api.spi.ShadowFieldExecutionResults
import viaduct.engine.runtime.Cell
import viaduct.engine.runtime.EngineExecutionContextExtensions.dispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldRssOriginFilteringKillSwitchEnabled
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.FetchedValueWithExtensions
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.HasResolver
import viaduct.engine.runtime.LazyEngineObjectData
import viaduct.engine.runtime.MatSource
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setCheckerValue
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setRawValue
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.exceptions.FieldFetchingException
import viaduct.engine.runtime.execution.FieldExecutionHelpers.buildDataFetchingEnvironment
import viaduct.engine.runtime.execution.FieldExecutionHelpers.buildOERKeyForField
import viaduct.engine.runtime.execution.FieldExecutionHelpers.collectFields
import viaduct.engine.runtime.execution.FieldExecutionHelpers.executionStepInfoFactory
import viaduct.engine.runtime.fetchFieldResultForResolver
import viaduct.engine.runtime.mat.KeyTree
import viaduct.engine.runtime.mat.LedgerReader
import viaduct.utils.slf4j.ifDebug
import viaduct.utils.slf4j.logger

/** Carries an ancestor's execution scope through parent-field fetching and completion. */
internal data class ParentFieldValue(
    val parameters: ExecutionParameters,
)

/**
 * A core component of the Viaduct execution engine responsible for resolving GraphQL field values and managing the
 * execution of data fetchers (https://spec.graphql.org/draft/#sec-Value-Resolution).
 *
 * The FieldResolver handles three main responsibilities:
 * 1. Object resolution - Coordinating the fetching and resolution of the collected fields of GraphQL object type
 * 2. Field resolution - Managing the execution of individual field data fetchers and processing their results
 * 3. Nested resolution - Handling nested object types and list fields by recursively resolving their values
 *
 * This class implements Viaduct's execution strategy which includes:
 * - Support for both serial and parallel field resolution
 * - Memoization of resolved values in [ObjectEngineResult] to prevent redundant fetches
 * - Comprehensive error handling and propagation through the execution tree
 * - Instrumentation support for monitoring and debugging the execution process
 * - Type resolution for interface and union types
 * - Proper handling of null values and list types
 *
 * Key features:
 * - Maintains execution path information for precise error tracking
 * - Supports GraphQL's partial results by isolating field resolution failures
 * - Integrates with GraphQL-Java's instrumentation system
 * - Handles both synchronous and asynchronous data fetcher results
 *
 * @see ObjectEngineResult
 * @see ExecutionParameters
 * @see FieldResolutionResult
 * @see CollectFields
 */
class FieldResolver(
    private val accessCheckRunner: AccessCheckRunner,
    private val coroutineInterop: CoroutineInterop,
) {
    companion object {
        private val log by logger()
    }

    /**
     * The values a field's fetch produces: its raw resolution result and its access-check result.
     *
     * @property result The [FieldResolutionResult] produced for the field.
     * @property checkerResult The field's access-check outcome, including its return type when applicable.
     */
    private data class FieldFetchResult(
        val result: Value<FieldResolutionResult>,
        val checkerResult: Value<out CheckerResult?>,
    )

    /**
     * A raw field fetch together with the values needed to finish its instrumentation.
     *
     * @property fetchedValue The field's value, errors, local context, and extensions.
     * @property dataFetcherResult The original fetch result reported to instrumentation.
     * @property instrumentationContext The instrumentation callback for this field fetch.
     */
    private data class RawFieldFetch(
        val fetchedValue: Value<FetchedValueWithExtensions>,
        val dataFetcherResult: Value<out Any?>,
        val instrumentationContext: FieldFetchingInstrumentationContext,
    )

    /** Identifies where the raw value for a logical field comes from. */
    private sealed interface FieldFetchSource {
        /** Fetches the field using the data fetcher registered in the schema's code registry. */
        data object RegisteredDataFetcher : FieldFetchSource

        /**
         * Fetches an exact field key from a [viaduct.engine.runtime.mat.MatLedger] that already
         * covers the requested field.
         */
        data class Ledger(val reader: LedgerReader, val key: ObjectEngineResult.Key) : FieldFetchSource
    }

    /**
     * Fetches an object by resolving all of its selected fields.
     *
     * This method:
     * 1. Runs CollectFields on the current uncollected selection set
     * 2. Fires off field fetches for each merged object selection, in parallel when [serialDispatch] is
     *   false. When [serialDispatch] is true, a fetch is only initiated when the previous selection has
     *   completed fetching (either successfully or exceptionally)
     *
     * Note on return value: This method returns `Value<Unit>` instead of `Value<Map<String, FieldResolutionResult>>`
     * because the actual resolved values are stored directly in the `ObjectEngineResult` associated with
     * the parent object. The `Value<Unit>` serves as a completion signal for the orchestration layer to
     * know when all nested fetching (including any lazy data or nested objects) has finished.
     *
     * If the Value returned by this method is exceptionally completed, that means that there has been
     * a fatal error in resolving this object, and the parent ObjectEngineResult may be incomplete. Thus, callers of this
     * method should check for exceptional completion and handle it appropriately.
     *
     * @param parameters ExecutionParameters containing the execution context and selection set
     * @param serialDispatch Whether the selected fields must be resolved one at a time, in selection order
     * @throws Exception Only if there's a fatal error in the supervisorScope itself
     */
    fun fetchObject(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters,
        serialDispatch: Boolean = false,
    ): Value<Unit> =
        prepareLedgerReader(parameters).flatMap { ledgerReader ->
            fetchObjectInternal(objectType, parameters, ledgerReader, serialDispatch)
        }

    @Suppress("UNUSED_EXPRESSION") // onCompleted calls are side-effects inside map/recover
    private fun fetchObjectInternal(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters,
        ledgerReader: LedgerReader?,
        serialDispatch: Boolean,
    ): Value<Unit> {
        val instrumentationParameters =
            InstrumentationExecutionStrategyParameters(parameters.executionContextWithLocalContext, parameters.gjParameters)
        val resolveObjectCtx = nonNullCtx(
            parameters.instrumentation.beginFetchObject(
                instrumentationParameters,
                parameters.executionContext.instrumentationState
            )
        )
        resolveObjectCtx.onDispatched()
        try {
            val fields = collectFields(objectType, parameters).selections
            val dispatch = if (serialDispatch) {
                dispatchFieldsSerially(objectType, parameters, fields, ledgerReader)
            } else {
                dispatchFieldsInParallel(objectType, parameters, fields, ledgerReader)
            }

            val currentOER = parameters.currentObjectEngineResult
            // We don't use the result of this operation, but we need to ensure it's scheduled
            // so that the resolution state is updated once every field's value is ready.
            dispatch.shallow.thenApply { _, _ ->
                currentOER.fieldResolutionState.complete(Unit)
            }

            // Wait for all values to be completed.
            return dispatch.deep
                .map {
                    resolveObjectCtx.onCompletedNullable(Unit, null)
                    it
                }.recover { t ->
                    resolveObjectCtx.onCompletedNullable(null, t)
                    Value.fromThrowable(t)
                }
        } catch (e: Exception) {
            resolveObjectCtx.onCompletedNullable(null, e)
            throw e
        }
    }

    /** Fires off a fetch for every field at once. */
    private fun dispatchFieldsInParallel(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters,
        fields: List<QueryPlan.Selection>,
        ledgerReader: LedgerReader?,
    ): Dispatch<Unit> {
        val results = fields.map { field ->
            field as QueryPlan.CollectedField
            val newParams = parameters.forField(objectType, field)
            resolveField(newParams, field, ledgerReader)
        }
        return Dispatch(
            shallow = Value.waitAll(results.map { it.shallow }),
            deep = Value.waitAll(results.map { it.deep }),
        )
    }

    /** Chains the field fetches so that each one only starts once the previous one has completed. */
    private fun dispatchFieldsSerially(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters,
        fields: List<QueryPlan.Selection>,
        ledgerReader: LedgerReader?,
    ): Dispatch<Unit> {
        val initial: Value<Unit> = Value.fromValue(Unit)
        val fieldValues = mutableListOf<Value<FieldResolutionResult>>()

        // iterate over each field to build a chained execution
        // Each field will kick off only after the previous one completes
        val deep = fields.fold(initial) { acc, field ->
            field as QueryPlan.CollectedField
            acc.flatMap { _ ->
                val fieldParameters = parameters.forField(objectType, field)
                val fd = resolveField(fieldParameters, field, ledgerReader)
                fieldValues.add(fd.shallow)
                fd.deep
            }
        }
        return Dispatch(
            shallow = Value.waitAll(fieldValues),
            deep = deep,
        )
    }

    /**
     * Resolves a single field by coordinating its fetching, value resolution and error wrapping.
     * All errors are captured in the returned Value rather than thrown.
     *
     * This method:
     * 1. Creates field execution path
     * 2. Sets up new execution parameters
     * 3. Delegates to [executeField] which returns a Value of [FieldResolutionResult]
     *
     * @param parameters ExecutionParameters containing the context and execution state
     * @param field The field from the query plans to resolve
     * @param resolveNestedSelections Whether to traverse selections on the resolved field value
     */
    internal fun resolveField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        ledgerReader: LedgerReader? = null,
        resolveNestedSelections: Boolean = true,
    ): Dispatch<FieldResolutionResult> {
        if (!parameters.engineExecutionContext.fieldRssOriginFilteringKillSwitchEnabled) {
            val runtimeObjectType = checkNotNull(parameters.executionStepInfo.objectType) {
                "Expected executionStepInfo.objectType to be non-null while resolving ${field.fieldName}"
            }
            field.childPlans.forEach { childPlan ->
                val (originParentType, originFieldName) = childPlan.originCoordinate
                if (originFieldName != field.fieldName ||
                    originParentType != runtimeObjectType.name
                ) {
                    error(
                        "FieldChildPlan with originCoordinate=${childPlan.originCoordinate} leaked into resolution of " +
                            "${runtimeObjectType.name}.${field.fieldName}"
                    )
                }
            }
        }
        return executeField(parameters, field, ledgerReader, resolveNestedSelections)
    }

    private fun launchFieldChildPlans(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField
    ) {
        val fieldResolverDispatcher =
            parameters.engineExecutionContext.dispatcherRegistry.getFieldResolverDispatcher(
                parameters.currentObjectEngineResult.type.name,
                field.fieldName,
            )
        val objectSelectionSetId = fieldResolverDispatcher?.objectSelectionSet?.id
        val querySelectionSetId = fieldResolverDispatcher?.querySelectionSet?.id
        val resolverSelectionSetIds = setOfNotNull(objectSelectionSetId, querySelectionSetId)
        // Shadow RSS children retain the marker so @parent can be rejected. Only the compared
        // field bypasses its RSS execution condition.
        val isShadowRootField =
            parameters.isShadowFieldExecution &&
                (parameters.executionOrigin as? ExecutionOrigin.Field)
                    ?.parameters
                    ?.isShadowFieldExecution == false

        field.childPlans.fold(emptySet<RequiredSelectionSet.Id>()) { seenRssIds, childPlan ->
            if (childPlan.requiredSelectionSetId in seenRssIds) {
                return@fold seenRssIds
            }
            val isResolverSelectionSet = childPlan.requiredSelectionSetId in resolverSelectionSetIds
            val childQueryPlan = FieldExecutionHelpers.findRssQueryPlan(childPlan.requiredSelectionSetId, parameters)
            log.ifDebug {
                debug("Launching child plan for field ${field.fieldName} at path ${parameters.path}, selection set: ${childQueryPlan.selectionSet}")
            }
            val target = when (childPlan.requiredSelectionSetId) {
                objectSelectionSetId -> ChildQueryPlanTarget.CurrentObjectResult
                querySelectionSetId -> ChildQueryPlanTarget.CurrentQueryResult
                else -> parameters.targetForChildPlan(childQueryPlan)
            }
            launchQueryPlan(
                parameters,
                childQueryPlan,
                target = target,
                seenRssIds = seenRssIds,
                forceExecution =
                    isShadowRootField && isResolverSelectionSet,
            )
            seenRssIds + childPlan.requiredSelectionSetId
        }
    }

    /**
     * A handle on an in-flight dispatch of a field, or of a whole object's fields, for resolution.
     * The two properties are the shallow and deep completion signals for that dispatch.
     *
     * For a single field ([Dispatch]<[FieldResolutionResult]>, as returned by [resolveField]),
     * [shallow] carries the field's own resolved value. For a whole object
     * ([Dispatch]<[Unit]>, as returned by [dispatchFieldsInParallel] and [dispatchFieldsSerially]),
     * the per-field values live in the [ObjectEngineResult] instead, so [shallow] is only a
     * completion signal covering every field of the object.
     *
     * @property shallow A [Value] that completes when the field's data fetcher has finished
     *   and the [FieldResolutionResult] is available. Nested objects and lazy data underneath the
     *   field may still be pending.
     * @property deep A [Value] that completes after [shallow] and field execution
     *   instrumentation. When nested selection resolution is enabled, it also waits for nested
     *   objects and lazy data; shallow-only dispatches do not traverse them.
     */
    internal data class Dispatch<T>(
        val shallow: Value<T>,
        val deep: Value<Unit>
    )

    /**
     * Launches a child query plan by checking its execution condition, recursively launching
     * nested child plans, then resolving variables and calling fetchObject.
     *
     * @param parameters The execution parameters for the current field
     * @param plan The child query plan to launch
     * @param executionConditionEnv The DataFetchingEnvironment to evaluate the execution condition against, or null
     *        if no DFE is available at plan launch time. Null is a valid value per the QueryPlanExecutionCondition contract.
     * @param target Controls OER/source/stepInfo selection for non-Query plans
     * @param forceExecution Whether to bypass this plan's execution condition
     */
    internal fun launchQueryPlan(
        parameters: ExecutionParameters,
        plan: QueryPlan,
        executionConditionEnv: DataFetchingEnvironment? = null,
        target: ChildQueryPlanTarget,
        seenRssIds: Set<RequiredSelectionSet.Id> = emptySet(),
        forceExecution: Boolean = false,
    ) {
        val requiredSelectionSetId = plan.requiredSelectionSetId
        if (requiredSelectionSetId != null && requiredSelectionSetId in seenRssIds) {
            return
        }

        if (!forceExecution && !plan.executionCondition.shouldExecute(executionConditionEnv)) {
            log.ifDebug {
                debug(
                    "Skipping child plan for field '${parameters.field?.fieldName}' of type '${(plan.parentType as? GraphQLObjectType)?.name}' with selection set '${plan.selectionSet}' due to execution condition"
                )
            }
            return
        }

        plan.childPlanIds.fold(
            requiredSelectionSetId?.let { seenRssIds + it } ?: seenRssIds
        ) { accSeenRssIds, childPlanId ->
            if (childPlanId in accSeenRssIds) {
                return@fold accSeenRssIds
            }
            val childPlan = FieldExecutionHelpers.findRssQueryPlan(childPlanId, parameters)
            val childTarget = when (target) {
                is ChildQueryPlanTarget.ResolvedFieldObjectResult -> target
                is ChildQueryPlanTarget.IsolatedRootResults -> target
                else -> parameters.targetForChildPlan(childPlan)
            }
            launchQueryPlan(parameters, childPlan, executionConditionEnv, childTarget, seenRssIds)
            accSeenRssIds + childPlanId
        }

        parameters.launchOnRootScope {
            val executionTarget = parameters.childPlanVariableResolutionTarget(plan, target)
            val variables = FieldExecutionHelpers.resolveQueryPlanVariables(
                plan,
                parameters.executionStepInfo.arguments,
                executionTarget.currentObjectEngineResult,
                executionTarget.queryEngineResult,
                parameters.engineExecutionContext,
                parameters.executionContext.graphQLContext,
                parameters.executionContext.locale,
            )
            val planParameters = parameters.forChildPlan(plan, variables, target)
            val objectType = planParameters.currentObjectEngineResult.type
            fetchObject(objectType, planParameters, serialDispatch = isMutationNamespace(planParameters, objectType))
        }
    }

    /**
     * Executes a field by coordinating fetching and result processing.
     *
     * This method:
     * 1. Validates the parent ObjectEngineResult
     * 2. Sets up instrumentation
     * 3. Handles already-pending field fetches
     * 4. Coordinates field resolution and access checker execution
     * 5. Updates ObjectEngineResult with results or errors
     * 6. All errors are caught and included in the returned [Value]
     *
     * @param parameters The execution parameters containing field and context information
     */
    @Suppress("UNCHECKED_CAST")
    private fun executeField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        ledgerReader: LedgerReader?,
        resolveNestedSelections: Boolean,
    ): Dispatch<FieldResolutionResult> {
        // We're fetching an individual field; the current engine result will always be an ObjectEngineResult
        val currentOER = parameters.currentObjectEngineResult
        val executionStepInfoForField = parameters.executionStepInfo
        val dataFetchingEnvironmentProvider =
            FpKit.intraThreadMemoize { buildDataFetchingEnvironment(parameters, field, currentOER) }
        val oerKey = buildOERKeyForField(parameters, field)
        val isParentField = executionStepInfoForField.fieldDefinition.isParentField()
        val instrumentationParameters =
            InstrumentationFieldParameters(parameters.executionContextWithLocalContext) {
                executionStepInfoForField
            }
        val fieldInstrumentationCtx =
            if (isParentField) {
                null
            } else {
                nonNullCtx(
                    parameters.instrumentation.beginFieldExecution(
                        instrumentationParameters,
                        parameters.executionContext.instrumentationState
                    )
                )
            }

        fieldInstrumentationCtx?.onDispatched()

        // Check if the field is already being fetched, and if so, we can await the pending and return the result
        val fieldResolutionResultValue: Value<FieldResolutionResult> = currentOER.computeIfAbsent(oerKey) { slotSetter ->
            log.ifDebug {
                debug("Field @ {} with OER key: {} is not being fetched, fetching now...", parameters.path, oerKey)
            }
            launchFieldChildPlans(parameters, field)
            val fieldFetchResult =
                if (isParentField) {
                    fetchParentField(field, parameters, dataFetchingEnvironmentProvider)
                } else {
                    fetchField(
                        field,
                        parameters,
                        fieldFetchSource(ledgerReader, oerKey),
                        dataFetchingEnvironmentProvider,
                    )
                }
            slotSetter.setRawValue(fieldFetchResult.result)
            slotSetter.setCheckerValue(fieldFetchResult.checkerResult)
        } as Value<FieldResolutionResult>

        val deep = fieldResolutionResultValue.thenCompose { v, e ->
            fieldInstrumentationCtx?.onCompletedNullable(v, e)
            if (e != null || !resolveNestedSelections) {
                // Failed fields and shallow-only callers do not traverse nested objects.
                Value.fromValue(Unit)
            } else {
                // otherwise, proceed with nested object resolution
                maybeFetchNestedObject(
                    v!!,
                    executionStepInfoForField.type,
                    field,
                    parameters.copy(
                        executionStepInfo = executionStepInfoForField,
                    )
                )
            }
        }

        val productionDispatch = Dispatch(
            shallow = fieldResolutionResultValue,
            deep = deep
        )
        if (parameters.isShadowFieldExecution) {
            return productionDispatch
        }

        val shadowComparison =
            requestShadowFieldExecution(parameters, instrumentationParameters)
        if (shadowComparison == null) {
            return productionDispatch
        }
        if (isMutationOrSubscriptionField(parameters)) {
            log.warn(
                "Ignoring shadow field execution request for unsupported field {}.{}",
                parameters.executionStepInfo.objectType.name,
                field.fieldName,
            )
            return productionDispatch
        }

        launchShadowFieldExecution(
            parameters,
            field,
            productionDispatch,
            shadowComparison,
        )
        return productionDispatch
    }

    @Suppress("TooGenericExceptionCaught")
    private fun requestShadowFieldExecution(
        parameters: ExecutionParameters,
        instrumentationParameters: InstrumentationFieldParameters,
    ): ShadowFieldExecutionComparison? =
        try {
            parameters.instrumentation.requestShadowFieldExecution(
                instrumentationParameters,
                parameters.executionContext.instrumentationState,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "Failed to request shadow field execution for {}",
                parameters.path,
                e,
            )
            null
        }

    @Suppress("TooGenericExceptionCaught")
    private fun launchShadowFieldExecution(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        productionDispatch: Dispatch<FieldResolutionResult>,
        comparison: ShadowFieldExecutionComparison,
    ) {
        parameters.launchOnRootScope {
            try {
                val shadowOutcome = executeShadowField(parameters, field)
                val results =
                    ShadowFieldExecutionResults(
                        production = productionDispatch.captureOutcome(),
                        shadow = shadowOutcome,
                    )
                compareShadowFieldExecutionResults(parameters, comparison, results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(
                    "Unexpected failure while executing shadow field at {}",
                    parameters.path,
                    e,
                )
            } catch (e: Error) {
                throw e
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeShadowField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
    ): ShadowFieldExecutionResults.Outcome =
        supervisorScope {
            try {
                validateRegisteredFieldResolver(parameters, field)
                val shadowParameters =
                    parameters.forShadowFieldExecution(coroutineContext)
                resolveField(
                    parameters = shadowParameters,
                    field = field,
                    resolveNestedSelections = false,
                ).captureOutcome()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                failedFieldExecutionOutcome(e)
            } catch (e: Exception) {
                failedFieldExecutionOutcome(e)
            }
        }

    private fun validateRegisteredFieldResolver(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
    ) {
        val objectType = checkNotNull(parameters.executionStepInfo.objectType)
        checkNotNull(
            parameters.engineExecutionContext.dispatcherRegistry
                .getFieldResolverDispatcher(objectType.name, field.fieldName)
        ) {
            "No registered field resolver exists for ${objectType.name}.${field.fieldName}"
        }
    }

    private suspend fun Dispatch<FieldResolutionResult>.captureOutcome(): ShadowFieldExecutionResults.Outcome {
        val fieldResolutionResult = captureFieldExecutionResult { shallow.await() }
        return ShadowFieldExecutionResults.Outcome(
            rawValue = fieldResolutionResult.map { it.originalSource },
            graphqlErrors = fieldResolutionResult.getOrNull()?.errors.orEmpty().toList(),
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> captureFieldExecutionResult(block: suspend () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun failedFieldExecutionOutcome(throwable: Throwable): ShadowFieldExecutionResults.Outcome =
        ShadowFieldExecutionResults.Outcome(
            rawValue = Result.failure(throwable),
            graphqlErrors = emptyList(),
        )

    @Suppress("TooGenericExceptionCaught")
    private fun compareShadowFieldExecutionResults(
        parameters: ExecutionParameters,
        comparison: ShadowFieldExecutionComparison,
        results: ShadowFieldExecutionResults,
    ) {
        try {
            comparison.compare(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "Shadow field execution comparison failed for {}",
                parameters.path,
                e,
            )
        }
    }

    /**
     * Resolves a field through the normal data-fetcher pipeline and returns its shallow result
     * without traversing the returned value's nested selections.
     *
     * Required selection plans, Mats, access checks, and instrumentation still run normally.
     * Resolver and access-check errors are thrown rather than returned in the result.
     */
    suspend fun resolveShallowFieldResult(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
    ): FieldResolutionResult {
        val dispatch = resolveField(
            parameters = parameters,
            field = field,
            ledgerReader = null,
            resolveNestedSelections = false,
        )

        // Await full resolution first so fatal instrumentation completion errors take precedence.
        dispatch.deep.await()
        val oerKey = buildOERKeyForField(parameters, field)
        val fieldDirectives = FieldExecutionHelpers.engineSelectionSet(
            parameters = parameters,
            projectionType = parameters.currentObjectEngineResult.type,
            selectionSet = parameters.selectionSet,
            fragments = parameters.queryPlan.fragments,
        ).fieldDirectivesOfSelection(
            parameters.currentObjectEngineResult.type.name,
            field.responseKey,
        )
        return parameters.currentObjectEngineResult.fetchFieldResultForResolver(
            oerKey,
            fieldDirectives,
        )
    }

    private fun fetchParentField(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
    ): FieldFetchResult {
        if (parameters.isShadowFieldExecution) {
            val failure = IllegalStateException("@parent fields are not supported during shadow field execution")
            return FieldFetchResult(
                result = Value.fromThrowable(failure),
                checkerResult = Value.fromThrowable(failure),
            )
        }

        val fieldCheckerResult = accessCheckRunner.fieldCheck(parameters, dataFetchingEnvironmentProvider)
        val result = fieldResolutionResultFromDataFetcherResult(
            field,
            parameters,
            parameters.executionStepInfo.unwrappedNonNullType,
            parentFieldValue(parameters),
            dataFetchingEnvironmentProvider,
        )
        return FieldFetchResult(
            result = result,
            checkerResult = fieldCheckerResult,
        )
    }

    private val typeResolver = ResolveType()

    /**
     * Builds a FieldResolutionResult based on the field type and fetched data.
     * Any type mismatches or processing errors are thrown. All list items
     * stored in [Cell]s.
     *
     * This method handles:
     * - Null values
     * - Lists (recursively processes items)
     * - Leaf types (scalars/enums)
     * - Interface/Union types
     * - Object types
     *
     * @param parameters The execution parameters
     * @param fieldType The GraphQL output type
     * @param fetchedValue The FetchedValue containing raw data
     * @param dataFetchingEnvironmentProvider Provides the DFE for lazy resolution ([LazyEngineObjectData]).
     * @return [Value] of [FieldResolutionResult]
     */
    private fun buildFieldResolutionResult(
        parameters: ExecutionParameters,
        fieldType: GraphQLOutputType,
        fetchedValue: FetchedValue,
        resolutionPolicy: ResolutionPolicy,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
        memberIndices: List<Int> = emptyList(),
    ): Value<FieldResolutionResult> {
        val field = checkNotNull(parameters.field) { "Expected parameters.field to be non-null." }
        val data = fetchedValue.fetchedValue ?: return syncFieldResolutionResult(null, fetchedValue, resolutionPolicy)

        // Unwrap data from "ParentManagedValue" or "StandardResolutionValue" if necessary, and set the effective resolution policy
        val (effectiveData, effectiveResolutionPolicy) =
            FieldExecutionHelpers.unwrapResolutionValue(data, resolutionPolicy)

        if (effectiveData == null) {
            return syncFieldResolutionResult(null, fetchedValue, effectiveResolutionPolicy)
        }

        // If the type has a non-null wrapper, unwrap one level and recurse
        if (GraphQLTypeUtil.isNonNull(fieldType)) {
            return buildFieldResolutionResult(parameters, GraphQLTypeUtil.unwrapNonNullAs(fieldType), fetchedValue, effectiveResolutionPolicy, dataFetchingEnvironmentProvider, memberIndices)
        }

        if (effectiveData is ParentFieldValue) {
            return syncFieldResolutionResult(
                effectiveData.parameters.currentObjectEngineResult,
                fetchedValue,
                effectiveResolutionPolicy,
                originalSource = effectiveData,
            )
        }

        // When it's a list, wrap each item in the list
        if (GraphQLTypeUtil.isList(fieldType)) {
            val newFieldType = GraphQLTypeUtil.unwrapOneAs<GraphQLOutputType>(fieldType)
            val resultIterable = checkNotNull(effectiveData as? Iterable<*>) {
                "Expected data to be an Iterable, was ${effectiveData.javaClass}."
            }
            return syncFieldResolutionResult(
                resultIterable.mapIndexed { index, it ->
                    val itemFV = FieldExecutionHelpers.toFetchedValueOrThrow(parameters, it)
                    ObjectEngineResultImpl.newCell { slotSetter ->
                        val itemFieldResolutionResult = buildFieldResolutionResult(
                            parameters,
                            newFieldType,
                            itemFV,
                            effectiveResolutionPolicy,
                            dataFetchingEnvironmentProvider,
                            memberIndices + index,
                        )
                        slotSetter.setRawValue(itemFieldResolutionResult)
                        val typeCheckerResult = itemFieldResolutionResult.thenCompose { itemFrr, _ ->
                            val oer = itemFrr?.engineResult as? ObjectEngineResultImpl
                            if (oer == null) {
                                Value.nullValue
                            } else {
                                val newParams = updateListItemParameters(parameters, index)
                                val itemDfeSupplier: () -> DataFetchingEnvironment = { buildDataFetchingEnvironment(newParams, field, parameters.currentObjectEngineResult) }
                                accessCheckRunner.typeCheck(newParams, itemDfeSupplier, oer, itemFrr, this@FieldResolver)
                            }
                        }
                        slotSetter.setCheckerValue(typeCheckerResult)
                    }
                },
                fetchedValue,
                effectiveResolutionPolicy,
                originalSource = effectiveData,
            )
        }

        // When it's a leaf value, it doesn't need wrapping
        if (GraphQLTypeUtil.isLeaf(fieldType)) {
            return syncFieldResolutionResult(effectiveData, fetchedValue, effectiveResolutionPolicy, originalSource = effectiveData)
        }

        // Interface or union type, resolve the type and wrap it
        if (GraphQLTypeUtil.isInterfaceOrUnion(fieldType)) {
            val resolvedType = typeResolver.resolveType(
                parameters.executionContext,
                field.mergedField,
                effectiveData,
                parameters.executionStepInfo,
                fieldType,
                fetchedValue.localContext
            )
            return buildFieldResolutionResult(parameters, resolvedType, fetchedValue, effectiveResolutionPolicy, dataFetchingEnvironmentProvider, memberIndices)
        }

        // When it's an object, wrap the whole thing
        if (fieldType is GraphQLObjectType) {
            return mkOER(
                parameters = parameters,
                field = field,
                fieldType = fieldType,
                effectiveData = effectiveData,
                fetchedValue = fetchedValue,
                resolutionPolicy = effectiveResolutionPolicy,
                memberIndices = memberIndices,
            ).map { oer ->
                FieldResolutionResult.fromFetchedValue(
                    oer,
                    fetchedValue,
                    effectiveResolutionPolicy,
                    originalSource = effectiveData,
                )
            }
        }
        throw IllegalStateException("ObjectEngineResult must wrap a GraphQLObjectType.")
    }

    /**
     * Prepares field-level ledger reads for a Mat-backed object.
     *
     * Null means the object has no Mat backing. Preparation failures become readers that throw
     * when accessed so they can be reported by consuming fields, while cancellation completes the
     * outer [Value] exceptionally.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun prepareLedgerReader(parameters: ExecutionParameters): Value<LedgerReader?> {
        val oer = parameters.currentObjectEngineResult

        if (oer.matSource == null) {
            return Value.fromValue(null)
        }

        val deferred = CompletableDeferred<LedgerReader?>()
        parameters.launchOnRootScope {
            try {
                val matParameters = MatParameters.create(
                    oer,
                    parameters.queryPlan.keyTree(parameters),
                    parameters,
                )
                matParameters.ledger.ensureCoverage(
                    matParameters.requestedShape,
                    matParameters.parameters,
                )
                deferred.complete(
                    LedgerReader(
                        matParameters.ledger,
                        matParameters.path,
                        matParameters.requestedShape,
                        matParameters.rootNodeId,
                    )
                )
            } catch (e: CancellationException) {
                deferred.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                deferred.complete(LedgerReader.failed(e))
            }
        }
        return Value.fromDeferred(deferred)
    }

    /** create a [MatSource] for a field Mat */
    private suspend fun mkFieldMatLedgerSource(
        parameters: ExecutionParameters,
        effectiveData: EngineObjectData,
        memberIndices: List<Int>,
    ): MatSource {
        val ossFilter = FieldOutputSelectionSetFilter(
            HasResolver.fromRegistry(parameters.engineExecutionContext.dispatcherRegistry)
        )
        val mat = FieldMatImpl(
            parameters,
            ossFilter,
            materialize = { keyTree, selectionParameters ->
                matFieldObject(
                    originalParameters = parameters,
                    originalField = checkNotNull(parameters.field),
                    keyTree = keyTree,
                    selectionParameters = selectionParameters,
                    memberIndices = memberIndices,
                    expectedType = effectiveData.type,
                )
            },
        )
        val ledger = MatLedgerImpl(mat)
        ledger.initialize(mat.resultFromInitialFetch(effectiveData))
        return MatSource.Ledger(ledger, ossFilter)
    }

    private fun mkOER(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        fieldType: GraphQLObjectType,
        effectiveData: Any,
        fetchedValue: FetchedValue,
        resolutionPolicy: ResolutionPolicy,
        memberIndices: List<Int>,
    ): Value<ObjectEngineResultImpl> {
        val nodeReference = effectiveData as? NodeEngineObjectData
        return when {
            effectiveData is LazyEngineObjectData &&
                nodeReference != null &&
                isNodeMatBacked(parameters, fieldType) ->
                mkNodeMatOER(
                    parameters = parameters,
                    field = field,
                    fieldType = fieldType,
                    reference = nodeReference,
                    lazyReference = effectiveData,
                    fetchedValue = fetchedValue,
                    resolutionPolicy = resolutionPolicy,
                )

            effectiveData is LazyEngineObjectData ->
                lazyObjectEngineResult(
                    parameters = parameters,
                    fieldType = fieldType,
                    lazyData = effectiveData,
                )

            // selective field resolver
            isFieldMatBacked(parameters, field, effectiveData) ->
                mkFieldMatOER(
                    parameters = parameters,
                    fieldType = fieldType,
                    effectiveData = effectiveData as EngineObjectData,
                    memberIndices = memberIndices,
                )

            // Resolver-less objects may inherit an embedded Mat from their parent.
            else ->
                Value.fromValue(
                    ObjectEngineResultImpl.newForType(
                        fieldType,
                        mkEmbeddedMatSource(parameters, field, fieldType, memberIndices),
                    )
                )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun mkFieldMatOER(
        parameters: ExecutionParameters,
        fieldType: GraphQLObjectType,
        effectiveData: EngineObjectData,
        memberIndices: List<Int>,
    ): Value<ObjectEngineResultImpl> {
        val deferred = CompletableDeferred<ObjectEngineResultImpl>()
        parameters.launchOnRootScope {
            try {
                deferred.complete(
                    ObjectEngineResultImpl.newForType(
                        fieldType,
                        mkFieldMatLedgerSource(parameters, effectiveData, memberIndices),
                    )
                )
            } catch (e: CancellationException) {
                deferred.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                deferred.completeExceptionally(e)
            }
        }
        return Value.fromDeferred(deferred)
    }

    /**
     * Creates an ObjectEngineResultImpl backed by a selective node resolver. The first requested
     * selection is loaded before the result is made available for object traversal.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun mkNodeMatOER(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        fieldType: GraphQLObjectType,
        reference: NodeEngineObjectData,
        lazyReference: LazyEngineObjectData,
        fetchedValue: FetchedValue,
        resolutionPolicy: ResolutionPolicy,
    ): Value<ObjectEngineResultImpl> {
        val outputSelectionSetFilter = NodeOutputSelectionSetFilter(
            HasResolver.fromRegistry(parameters.engineExecutionContext.dispatcherRegistry)
        )
        val mat = NodeMatImpl(
            ref = reference,
            outputSelectionSetFilter = outputSelectionSetFilter,
            materialize = { selections, selectionParameters ->
                resolveWithNodeFetchingInstrumentation(selectionParameters, reference.type) {
                    checkNotNull(
                        lazyReference.resolveData(
                            selections,
                            selectionParameters.engineExecutionContext,
                        )
                    ) {
                        "Node reference ${reference.type.name}(id:${reference.id}) resolved to null"
                    }
                }
            },
            launch = { selectionParameters, materializationPlan, keyTree ->
                launchMatPlan(selectionParameters, materializationPlan, keyTree)
            },
        )
        val ledger = MatLedgerImpl(mat)
        val matSource = MatSource.Ledger(ledger, outputSelectionSetFilter, reference.id)
        val engineResult = ObjectEngineResultImpl.newPendingForType(fieldType, matSource)
        val deferred = CompletableDeferred<ObjectEngineResultImpl>()
        parameters.launchOnRootScope {
            try {
                val traversalParameters = parameters.forObjectTraversal(
                    field,
                    engineResult,
                    fetchedValue.compositeLocalContext,
                    reference,
                    resolutionPolicy,
                )
                val materializationKeyTree = traversalParameters.queryPlan
                    .keyTree(traversalParameters)
                    .filter(nodeInitialResolutionFilter)
                    .withoutEmptyTypeBranches()
                if (materializationKeyTree.isEmpty()) {
                    engineResult.resolveToValue()
                    deferred.complete(engineResult)
                    return@launchOnRootScope
                }
                ledger.materializeInitial(materializationKeyTree, traversalParameters)
                    .source
                    .getOrThrow()
                engineResult.resolveToValue()
                deferred.complete(engineResult)
            } catch (e: CancellationException) {
                engineResult.resolveExceptionally(e)
                deferred.completeExceptionally(e)
                throw e
            } catch (e: Throwable) {
                engineResult.resolveExceptionally(e)
                deferred.completeExceptionally(e)
            }
        }
        return Value.fromDeferred(deferred)
    }

    /** Re-executes [originalField] for the requested Mat coverage. */
    private suspend fun matFieldObject(
        originalParameters: ExecutionParameters,
        originalField: QueryPlan.CollectedField,
        keyTree: KeyTree,
        selectionParameters: ExecutionParameters,
        memberIndices: List<Int>,
        expectedType: GraphQLObjectType,
    ): EngineObjectData? {
        val matPlan = materializationPlan(selectionParameters, keyTree)
        val matField = FieldExecutionHelpers.withMaterializationSelectionSet(
            originalField = originalField,
            originalParameters = originalParameters,
            selectionSet = matPlan.selectionSet,
        )
        val matParameters = originalParameters.forFieldMaterialization(
            field = matField,
            materializationPlan = matPlan,
            selectionParameters = selectionParameters,
        )
        val dataFetchingEnvironmentProvider =
            FpKit.intraThreadMemoize {
                buildDataFetchingEnvironment(
                    matParameters,
                    matField,
                    matParameters.currentObjectEngineResult,
                )
            }

        val rawFieldFetch = fetchRawFieldValue(
            field = matField,
            parameters = matParameters,
            fieldFetchSource = FieldFetchSource.RegisteredDataFetcher,
            dataFetchingEnvironmentProvider = dataFetchingEnvironmentProvider,
            gatingCheckerResult = null,
        )
        completeFieldFetching(rawFieldFetch)
        rawFieldFetch.dataFetcherResult.thenApply { result, error ->
            if (error == null && result is DataFetcherResult<*>) {
                // These are rejected because there is no obvious way to handle localContext returned by a resolver.
                throw materializationException(
                    "DataFetcherResult is not supported during selective field materialization; " +
                        "return data directly or throw an exception",
                    matParameters,
                )
            }
        }.await()
        val fetchedValue = rawFieldFetch.fetchedValue.await()
        val matSource = FieldExecutionHelpers.toMaterializedObjectData(
            matParameters,
            fetchedValue.fetchedValue,
            memberIndices,
        )
        if (matSource != null && matSource.type.name != expectedType.name) {
            throw materializationException(
                "materialized field result diverged: expected object of type " +
                    "`${expectedType.name}`, found `${matSource.type.name}`"
            )
        }
        if (matSource != null) {
            // Run matPlan on the object that was missing these fields, and keep the new depth for
            // the work it starts.
            val planParameters =
                selectionParameters.copy(matBatchDepth = matParameters.matBatchDepth)
            checkNotNull(planParameters.currentObjectEngineResult.matSource as? MatSource.Ledger)
            launchQueryPlan(
                planParameters,
                matPlan,
                target = ChildQueryPlanTarget.ResolvedFieldObjectResult(
                    planParameters.currentObjectEngineResult,
                    planParameters.source,
                ),
            )
        }
        return matSource
    }

    private fun launchMatPlan(
        selectionParameters: ExecutionParameters,
        matPlan: QueryPlan,
        keyTree: KeyTree,
    ) {
        if (keyTree.isEmpty()) return

        checkNotNull(selectionParameters.currentObjectEngineResult.matSource as? MatSource.Ledger)
        launchQueryPlan(
            selectionParameters,
            matPlan,
            target = ChildQueryPlanTarget.ResolvedFieldObjectResult(
                selectionParameters.currentObjectEngineResult,
                selectionParameters.source,
            ),
        )
    }

    /**
     * Wraps an engine result and fetched value into a synchronous [Value] of [FieldResolutionResult].
     */
    private fun syncFieldResolutionResult(
        engineResult: Any?,
        fetchedValue: FetchedValue,
        resolutionPolicy: ResolutionPolicy,
        originalSource: Any? = null,
    ): Value<FieldResolutionResult> = Value.fromValue(FieldResolutionResult.fromFetchedValue(engineResult, fetchedValue, resolutionPolicy, originalSource = originalSource))

    /**
     * Creates an ObjectEngineResultImpl in the pending state for a [LazyEngineObjectData], and
     * launches async resolution that resolves the OER when complete.
     */
    private fun lazyObjectEngineResult(
        parameters: ExecutionParameters,
        fieldType: GraphQLObjectType,
        lazyData: LazyEngineObjectData,
    ): Value<ObjectEngineResultImpl> {
        val engineResult = ObjectEngineResultImpl.newPendingForType(fieldType)
        parameters.launchOnRootScope {
            try {
                val result = resolveWithNodeFetchingInstrumentation(
                    parameters = parameters,
                    fieldType = fieldType,
                ) {
                    lazyData.resolveData(
                        checkNotNull(FieldExecutionHelpers.engineSelectionSet(parameters)),
                        parameters.engineExecutionContext,
                    )
                }
                if (result != null) {
                    engineResult.resolveToValue()
                } else {
                    engineResult.resolveToNull()
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                engineResult.resolveExceptionally(e)
            }
        }
        return Value.fromValue(engineResult)
    }

    private suspend fun <T> resolveWithNodeFetchingInstrumentation(
        parameters: ExecutionParameters,
        fieldType: GraphQLObjectType,
        resolve: suspend () -> T,
    ): T {
        val nodeResolverMetadata = (parameters.engineExecutionContext as? EngineExecutionContextImpl)
            ?.dispatcherRegistry?.getNodeResolverDispatcher(fieldType.name)?.resolverMetadata
        val nodeInstrCtx = parameters.instrumentation.beginNodeFetching(
            InstrumentNodeFetchingParameters(
                requiredBy = parameters.queryPlan.attribution,
                resolverMetadata = nodeResolverMetadata,
            ),
            parameters.executionContext.instrumentationState
        )
        nodeInstrCtx?.onDispatched()
        return try {
            val result = resolve()
            nodeInstrCtx?.onCompletedNullable(null, null)
            result
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            nodeInstrCtx?.onCompletedNullable(null, e)
            throw e
        }
    }

    /**
     * Initiates fetching of nested selection sets for complex field types.
     *
     * This method handles:
     * 1. List results by processing each item individually with indexed paths
     * 2. Object results by initiating a new fetchObject operation with the nested selection set
     * 3. Path management for nested fields to maintain proper error tracking
     *
     * @param fieldResolutionResult The result of the parent field execution
     * @param field the [QueryPlan.CollectedField] containing potential nested selections
     * @param parameters The [ExecutionParameters] for the current context
     *
     * @throws IllegalStateException if a selection set is missing for object types
     */
    private fun maybeFetchNestedObject(
        fieldResolutionResult: FieldResolutionResult,
        outputType: GraphQLOutputType,
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
    ): Value<Unit> {
        // if engineResult is null, then there is no nested object to fetch and we can return early
        if (fieldResolutionResult.engineResult == null) return Value.fromValue(Unit)
        return when (outputType) {
            is GraphQLNonNull -> maybeFetchNestedObject(fieldResolutionResult, GraphQLTypeUtil.unwrapOneAs(outputType), field, parameters)
            is GraphQLList -> {
                val engineResult = checkNotNull(fieldResolutionResult.engineResult as? Iterable<*>) { "Expected iterable engineResult but got ${fieldResolutionResult.engineResult}" }
                val values = engineResult.mapIndexed { i, item ->
                    check(item is Cell) { "Expected engine result to be a Cell." }
                    item.getValue(RAW_VALUE_SLOT).flatMap { raw ->
                        val frr = raw as? FieldResolutionResult
                            ?: throw IllegalStateException("Expected FieldResolutionResult but got $raw")
                        val newParams = updateListItemParameters(parameters, i)
                        maybeFetchNestedObject(frr, GraphQLTypeUtil.unwrapOneAs(outputType), field, newParams)
                    }.recover { Value.fromValue(Unit) } // Contain per-item failures so they don't propagate to the parent object
                }
                Value.waitAll(values)
            }

            else -> {
                // if engineResult is a scalar or simple value, then no nesting is possible and we can return
                val oer = fieldResolutionResult.engineResult as? ObjectEngineResultImpl ?: return Value.fromValue(Unit)
                val traversalParameters =
                    if (parameters.executionStepInfo.fieldDefinition.isParentField()) {
                        val parentFieldValue = fieldResolutionResult.originalSource as? ParentFieldValue
                            ?: throw IllegalStateException("Expected ParentFieldValue but got ${fieldResolutionResult.originalSource}")
                        parameters.forParentFieldTraversal(field, parentFieldValue.parameters, fieldResolutionResult.localContext, fieldResolutionResult.resolutionPolicy)
                    } else {
                        parameters.forObjectTraversal(field, oer, fieldResolutionResult.localContext, fieldResolutionResult.originalSource, fieldResolutionResult.resolutionPolicy)
                    }
                fetchObject(oer.type, traversalParameters, serialDispatch = isMutationNamespace(parameters, oer.type))
            }
        }
    }

    /**
     * Updates ExecutionParameter executionStepInfo for list items
     */
    private fun updateListItemParameters(
        parameters: ExecutionParameters,
        itemIndex: Int
    ): ExecutionParameters {
        val indexedPath = parameters.path.segment(itemIndex)
        val execStepInfoForItem =
            executionStepInfoFactory.newExecutionStepInfoForListElement(
                parameters.executionStepInfo,
                indexedPath
            )
        return parameters.copy(executionStepInfo = execStepInfoForItem)
    }

    /**
     * Fetches field data using the appropriate data fetcher.
     * All errors during fetching are caught and wrapped in Value.
     *
     * This method:
     * 1. Gets data fetcher
     * 2. Sets up instrumentation
     * 3. Executes the fetcher and the field checker if it exists
     * 4. Wraps any errors in FieldFetchingException before returning in [Value.fromThrowable]
     *
     * @param field The field to fetch
     * @param parameters The execution parameters
     * @param dataFetchingEnvironmentProvider Provider for the fetching environment
     * @return The executable field result and its access-check result.
     */
    private fun fetchField(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        fieldFetchSource: FieldFetchSource,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
    ): FieldFetchResult =
        try {
            // For top-level mutation and subscription fields, execute the data fetcher only if the access check succeeds.
            // For everything else, execute the access check in parallel with the data fetcher.
            val executeCheckerSequentially = shouldExecuteCheckerSequentially(parameters)

            val fieldType = parameters.executionStepInfo.unwrappedNonNullType
            val fieldCheckerResultValue = accessCheckRunner.fieldCheck(parameters, dataFetchingEnvironmentProvider)

            val rawFieldFetch = fetchRawFieldValue(
                field = field,
                parameters = parameters,
                fieldFetchSource = fieldFetchSource,
                dataFetchingEnvironmentProvider = dataFetchingEnvironmentProvider,
                gatingCheckerResult =
                    fieldCheckerResultValue.takeIf { executeCheckerSequentially },
            )
            val fieldResolutionResult = fieldResolutionResultFromFetchedValue(
                field,
                parameters,
                fieldType,
                rawFieldFetch.fetchedValue,
                dataFetchingEnvironmentProvider,
            )
            val checkerResult = accessCheckRunner.combineWithTypeCheck(
                parameters,
                dataFetchingEnvironmentProvider,
                fieldCheckerResultValue,
                fieldType,
                fieldResolutionResult,
                this
            )

            // Complete instrumentation exactly once. Data fetcher errors take priority over checker errors.
            // In sequential mode, field checker is checked first before executing the data fetcher.
            if (executeCheckerSequentially) {
                fieldCheckerResultValue.thenApply { fieldCheckerRes, fieldCheckerError ->
                    if (fieldCheckerRes is CheckerResult.Error || fieldCheckerError != null) {
                        // Field checker failed - complete instrumentation immediately (no DF executed, no type check)
                        rawFieldFetch.instrumentationContext.onCompletedNullable(
                            null,
                            fieldCheckerRes?.asError?.error ?: fieldCheckerError,
                        )
                    } else {
                        // Field checker passed - now check data fetcher result
                        completeFieldFetching(rawFieldFetch, checkerResult)
                    }
                }
            } else {
                // Parallel mode - check data fetcher result first
                completeFieldFetching(rawFieldFetch, checkerResult)
            }

            FieldFetchResult(
                result = fieldResolutionResult,
                checkerResult = checkerResult
            )
        } catch (e: Exception) {
            val error = InternalEngineException.wrapWithPathAndLocation(e, parameters.path, field.sourceLocation)
            FieldFetchResult(
                result = Value.fromThrowable(error),
                checkerResult = Value.fromThrowable(error)
            )
        }

    /**
     * Fetches a field's raw value from its selected source.
     *
     * This operation owns field fetching instrumentation, but it does not launch field child plans
     * or run access checks. When [gatingCheckerResult] is present, the data fetcher runs only if
     * that checker succeeds.
     */
    private fun fetchRawFieldValue(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        fieldFetchSource: FieldFetchSource,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
        gatingCheckerResult: Value<out CheckerResult?>?,
    ): RawFieldFetch {
        val dataFetcher = fieldFetchSource.asDataFetcher(parameters, field)
        val fieldDataFetcher =
            FieldExecutionHelpers.prepareFieldDataFetcher(
                parameters,
                dataFetchingEnvironmentProvider,
                dataFetcher,
            )
        val fieldFetchingInstCtx = parameters.instrumentation.beginFieldFetching(
            fieldDataFetcher.instrumentationParameters,
            parameters.executionContext.instrumentationState
        ) ?: FieldFetchingInstrumentationContext.NOOP

        fieldFetchingInstCtx.onDispatched()

        val instrumentedDataFetcher =
            FieldExecutionHelpers.instrumentDataFetcher(parameters, fieldDataFetcher)
        val executeDataFetcher = {
            FieldExecutionHelpers.executeDataFetcher(
                parameters,
                fieldDataFetcher.fieldDefinition,
                dataFetchingEnvironmentProvider,
                instrumentedDataFetcher,
            )
        }
        val dataFetcherResult =
            gatingCheckerResult?.thenCompose { checkerResult, checkerError ->
                if (checkerResult is CheckerResult.Error || checkerError != null) {
                    Value.nullValue
                } else {
                    executeDataFetcher()
                }
            } ?: executeDataFetcher()
        val fetchedValue = dataFetcherResult
            .thenCompose { value, error ->
                FieldExecutionHelpers.dataFetcherResultToValue(
                    field,
                    parameters,
                    value,
                    error,
                )
            }.recover { e ->
                val wrappedException = when (e) {
                    is FieldFetchingException -> e
                    is InternalEngineException -> e
                    else -> InternalEngineException.wrapWithPathAndLocation(
                        e,
                        parameters.path,
                        field.sourceLocation
                    )
                }
                Value.fromThrowable(wrappedException)
            }

        return RawFieldFetch(
            fetchedValue = fetchedValue,
            dataFetcherResult = dataFetcherResult,
            instrumentationContext = fieldFetchingInstCtx,
        )
    }

    private fun completeFieldFetching(
        rawFieldFetch: RawFieldFetch,
        checkerResult: Value<out CheckerResult?>? = null,
    ) {
        rawFieldFetch.dataFetcherResult.thenApply { dataFetcherValue, dataFetcherError ->
            if (dataFetcherError != null) {
                rawFieldFetch.instrumentationContext.onCompletedNullable(null, dataFetcherError)
            } else if (checkerResult == null) {
                rawFieldFetch.instrumentationContext.onCompletedNullable(dataFetcherValue, null)
            } else {
                checkerResult.thenApply { result, checkerError ->
                    rawFieldFetch.instrumentationContext.onCompletedNullable(
                        dataFetcherValue,
                        result?.asError?.error ?: checkerError,
                    )
                }
            }
        }
    }

    private fun fieldFetchSource(
        ledgerReader: LedgerReader?,
        key: ObjectEngineResult.Key,
    ): FieldFetchSource {
        if (ledgerReader == null || key.name.startsWith("__")) {
            return FieldFetchSource.RegisteredDataFetcher
        }
        // Fields with their own resolver are not included in the ledger and are resolved normally.
        if (!ledgerReader.canFetch(key)) return FieldFetchSource.RegisteredDataFetcher

        return FieldFetchSource.Ledger(ledgerReader, key)
    }

    private fun FieldFetchSource.asDataFetcher(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
    ): DataFetcher<*> =
        when (this) {
            FieldFetchSource.RegisteredDataFetcher ->
                parameters.graphQLSchema.codeRegistry.getDataFetcher(
                    FieldExecutionHelpers.coordinateOfField(parameters, field),
                    parameters.executionStepInfo.fieldDefinition,
                )

            is FieldFetchSource.Ledger ->
                DataFetcher<Any?> {
                    coroutineInterop.scopedFuture {
                        reader.fetchOrNull(key)
                    }
                }
        }

    private fun fieldResolutionResultFromDataFetcherResult(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        fieldType: GraphQLOutputType,
        dataFetcherResult: Value<out Any?>,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
    ): Value<FieldResolutionResult> {
        val rawValue = dataFetcherResult.thenCompose { value, error ->
            FieldExecutionHelpers.dataFetcherResultToValue(field, parameters, value, error)
        }
        return fieldResolutionResultFromFetchedValue(
            field,
            parameters,
            fieldType,
            rawValue,
            dataFetchingEnvironmentProvider,
        )
    }

    private fun fieldResolutionResultFromFetchedValue(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        fieldType: GraphQLOutputType,
        fetchedValue: Value<FetchedValueWithExtensions>,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
    ): Value<FieldResolutionResult> =
        fetchedValue
            .flatMap { fv ->
                buildFieldResolutionResult(parameters, fieldType, fv, parameters.resolutionPolicy, dataFetchingEnvironmentProvider)
            }.recover { e ->
                // handle any errors that occurred during building FieldResolutionResult
                val wrappedException = when (e) {
                    is FieldFetchingException -> e
                    is InternalEngineException -> e
                    else -> InternalEngineException.wrapWithPathAndLocation(
                        e,
                        parameters.path,
                        field.sourceLocation
                    )
                }
                Value.fromThrowable(wrappedException)
            }

    private fun parentFieldValue(parameters: ExecutionParameters): Value<Any?> {
        val ancestor = parameters.nearestObjectAncestor() ?: return Value.fromValue(null)
        return Value.fromValue(
            DataFetcherResult.newResult<ParentFieldValue>()
                .data(ParentFieldValue(parameters = ancestor))
                .localContext(ancestor.localContext)
                .build()
        )
    }

    private fun isMutationOrSubscriptionField(parameters: ExecutionParameters): Boolean {
        val parentType = parameters.executionStepInfo.objectType
        return when (parentType.name) {
            parameters.graphQLSchema.mutationType?.name,
            parameters.graphQLSchema.subscriptionType?.name -> true
            else -> isMutationNamespace(parameters, parentType)
        }
    }

    private fun shouldExecuteCheckerSequentially(parameters: ExecutionParameters): Boolean = isMutationOrSubscriptionField(parameters)

    private fun isMutationNamespace(
        parameters: ExecutionParameters,
        objectType: GraphQLObjectType,
    ): Boolean = parameters.engineExecutionContext.fullSchema.isMutationNamespaceType(objectType.name)
}
