package viaduct.engine.runtime.execution

import graphql.TrivialDataFetcher
import graphql.execution.DataFetcherResult
import graphql.execution.FetchedValue
import graphql.execution.ResolveType
import graphql.execution.instrumentation.FieldFetchingInstrumentationContext
import graphql.execution.instrumentation.SimpleInstrumentationContext.nonNullCtx
import graphql.execution.instrumentation.parameters.InstrumentationExecutionStrategyParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.execution.instrumentation.parameters.InstrumentationFieldParameters
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.LightDataFetcher
import graphql.util.FpKit
import java.util.concurrent.CompletionStage
import java.util.function.Supplier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import viaduct.deferred.asDeferred
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.ParentManagedValue
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.engineExecutionContext
import viaduct.engine.runtime.Cell
import viaduct.engine.runtime.FetchedValueWithExtensions
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.LazyEngineObjectData
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
import viaduct.logging.ifDebug
import viaduct.utils.slf4j.logger

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
    private val accessCheckRunner: AccessCheckRunner
) {
    companion object {
        private val log by logger()
    }

    /**
     * Fetches an object by resolving all of its selected fields in parallel.
     *
     * This method:
     * 1. Runs CollectFields on the current uncollected selection set
     * 2. Fires off field fetches for each merged object selection, in parallel
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
     * @throws Exception Only if there's a fatal error in the supervisorScope itself
     */
    fun fetchObject(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters
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
            val results = collectFields(objectType, parameters)
                .selections
                .map { field ->
                    field as QueryPlan.CollectedField
                    val newParams = parameters.forField(objectType, field)
                    resolveField(newParams, field)
                }

            val immediate = Value.waitAll(results.map { it.immediate })
            val overall = Value.waitAll(results.map { it.overall })

            val parentOER = parameters.parentEngineResult
            // We don't use the result of this operation, but we need to ensure it's scheduled
            // so that the resolution state is updated when the immediate values are ready.
            immediate.thenApply { _, throwable ->
                parentOER.fieldResolutionState.complete(Unit)
            }

            // Wait for all values to be completed.
            return overall
                .map {
                    resolveObjectCtx.onCompleted(Unit, null)
                    it
                }.recover { t ->
                    resolveObjectCtx.onCompleted(null, t)
                    Value.fromThrowable(t)
                }
        } catch (e: Exception) {
            resolveObjectCtx.onCompleted(null, e)
            throw e
        }
    }

    /**
     * Fetches an object by resolving all of its selected fields serially.
     *
     * This method:
     * 1. Runs CollectFields on the current uncollected selection set
     * 2. Iteratively fires off field fetches for each selection. A fetch is only
     *   initiated when the previous selection has completed fetching (either
     *   successfully or exceptionally)
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
     * @throws Exception Only if there's a fatal error in the supervisorScope itself
     */
    fun fetchObjectSerially(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters
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
            val initial: Value<Unit> = Value.fromValue(Unit)
            val immediateResults = mutableListOf<Value<FieldResolutionResult>>()

            // iterate over each field to build a chained execution
            // Each field will kick off only after the previous one completes
            val overall = fields.fold(initial) { acc, field ->
                field as QueryPlan.CollectedField
                acc.flatMap { _ ->
                    val fieldParameters = parameters.forField(objectType, field)
                    val fd = resolveField(fieldParameters, field)
                    immediateResults.add(fd.immediate)
                    fd.overall
                }
            }.map {
                resolveObjectCtx.onCompleted(Unit, null)
                it
            }.recover { t ->
                resolveObjectCtx.onCompleted(null, t)
                Value.fromThrowable(t)
            }
            Value.waitAll(immediateResults).thenApply { _, _ ->
                parameters.parentEngineResult.fieldResolutionState.complete(Unit)
            }
            return overall
        } catch (e: Exception) {
            resolveObjectCtx.onCompleted(null, e)
            throw e
        }
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
     */
    internal fun resolveField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField
    ): FieldDispatch {
        field.childPlans.forEach {
            log.ifDebug {
                debug("Launching child plan for field ${field.fieldName} at path ${parameters.path}, selection set: ${it.selectionSet}")
            }
            launchQueryPlan(parameters, it)
        }
        return executeField(parameters)
    }

    /**
     * Represents the result of dispatching a field for resolution.
     *
     * @property immediate A [Value] that completes when the field's data fetcher has finished
     *   and the [FieldResolutionResult] is available. This signals that the field's "immediate"
     *   value is ready, though nested objects or lazy data may still be pending.
     * @property overall A [Value] that completes when the field and all of its nested objects
     *   and lazy data have been fully resolved. Used to track when the entire field subtree
     *   is complete.
     */
    internal data class FieldDispatch(
        val immediate: Value<FieldResolutionResult>,
        val overall: Value<Unit>
    )

    private fun launchQueryPlan(
        parameters: ExecutionParameters,
        plan: QueryPlan
    ) {
        if (!plan.executionCondition.shouldExecute()) {
            return
        }

        plan.childPlans.forEach { launchQueryPlan(parameters, it) }

        // Produce the object data and field arguments for the current field and make them available to child
        // plan VariablesResolver.
        parameters.launchOnRootScope {
            val variables = FieldExecutionHelpers.resolveQueryPlanVariables(
                plan,
                parameters.executionStepInfo.arguments,
                parameters.parentEngineResult,
                parameters.queryEngineResult,
                parameters.engineExecutionContext,
                parameters.executionContext.graphQLContext,
                parameters.executionContext.locale,
            )
            val planParameters = parameters.forChildPlan(plan, variables)
            fetchObject(plan.parentType as GraphQLObjectType, planParameters)
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
    private fun executeField(parameters: ExecutionParameters): FieldDispatch {
        val field = checkNotNull(parameters.field) { "Expected field to be non-null." }

        // We're fetching an individual field; the current engine result will always be an ObjectEngineResult
        val parentOER = parameters.parentEngineResult
        val oerKey = buildOERKeyForField(parameters, field)
        val executionStepInfoForField = parameters.executionStepInfo

        val fieldInstrumentationCtx = parameters.instrumentation.beginFieldExecution(
            InstrumentationFieldParameters(parameters.executionContextWithLocalContext) { executionStepInfoForField },
            parameters.executionContext.instrumentationState
        ) ?: FieldFetchingInstrumentationContext.NOOP

        val dataFetchingEnvironmentProvider =
            FpKit.intraThreadMemoize { buildDataFetchingEnvironment(parameters, field, parentOER) }

        fieldInstrumentationCtx.onDispatched()

        // Check if the field is already being fetched, and if so, we can await the pending and return the result
        val fieldResolutionResultValue: Value<FieldResolutionResult> = parentOER.computeIfAbsent(oerKey) { slotSetter ->
            log.ifDebug {
                debug("Field @ {} with OER key: {} is not being fetched, fetching now...", parameters.path, oerKey)
            }
            val (result, checkerResult) = fetchField(field, parameters, dataFetchingEnvironmentProvider)
            slotSetter.setRawValue(result)
            slotSetter.setCheckerValue(checkerResult)
        } as Value<FieldResolutionResult>

        val overall = fieldResolutionResultValue.thenCompose { v, e ->
            fieldInstrumentationCtx.onCompleted(v, e)
            if (e != null) {
                // if the field resolution failed, don't attempt to fetch lazy data or nested objects
                // and mark this field as completed
                Value.fromValue(Unit)
            } else {
                // otherwise, proceed with lazy data fetching and nested object resolution

                // if the result contains lazy data, begin fetching it
                maybeFetchLazyData(
                    v!!,
                    executionStepInfoForField.type,
                    parameters,
                    dataFetchingEnvironmentProvider
                )

                maybeFetchNestedObject(
                    v,
                    executionStepInfoForField.type,
                    field,
                    parameters.copy(
                        executionStepInfo = executionStepInfoForField,
                    )
                )
            }
        }

        return FieldDispatch(fieldResolutionResultValue, overall)
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
     * @return FieldResolutionResult - errors during processing are thrown
     */
    private fun buildFieldResolutionResult(
        parameters: ExecutionParameters,
        fieldType: GraphQLOutputType,
        fetchedValue: FetchedValue,
        resolutionPolicy: ResolutionPolicy,
    ): FieldResolutionResult {
        val field = checkNotNull(parameters.field) { "Expected parameters.field to be non-null." }
        val data = fetchedValue.fetchedValue ?: return FieldResolutionResult.fromFetchedValue(null, fetchedValue, resolutionPolicy)

        // Unwrap data from "ParentManagedValue" if necessary, and set the effective resolution policy
        var effectiveResolutionPolicy = resolutionPolicy
        val effectiveData = if (data is ParentManagedValue) {
            effectiveResolutionPolicy = ResolutionPolicy.PARENT_MANAGED
            data.value
        } else {
            data
        }

        if (effectiveData == null) {
            return FieldResolutionResult.fromFetchedValue(null, fetchedValue, effectiveResolutionPolicy)
        }

        // if the type has a non-null wrapper, unwrap one level and recurse
        if (GraphQLTypeUtil.isNonNull(fieldType)) {
            return buildFieldResolutionResult(parameters, GraphQLTypeUtil.unwrapNonNullAs(fieldType), fetchedValue, effectiveResolutionPolicy)
        }

        // When it's a list, wrap each item in the list
        if (GraphQLTypeUtil.isList(fieldType)) {
            val newFieldType = GraphQLTypeUtil.unwrapOneAs<GraphQLOutputType>(fieldType)
            val resultIterable = checkNotNull(effectiveData as? Iterable<*>) {
                "Expected data to be an Iterable, was ${effectiveData.javaClass}."
            }
            return FieldResolutionResult.fromFetchedValue(
                resultIterable.mapIndexed { index, it ->
                    // Data could be a list of objects or DataFetcherResults, so unwrap them as we loop over
                    val itemFV = toFetchedValueOrThrow(parameters, it)
                    ObjectEngineResultImpl.newCell { slotSetter ->
                        val itemFieldResolutionResult = buildFieldResolutionResult(
                            parameters,
                            newFieldType,
                            itemFV,
                            effectiveResolutionPolicy
                        )
                        slotSetter.setRawValue(Value.fromValue(itemFieldResolutionResult))

                        // If this list item is an object, execute and store its type check in the checker slot
                        val oer = itemFieldResolutionResult.engineResult as? ObjectEngineResultImpl
                        val typeCheckerResult = if (oer == null) {
                            Value.nullValue
                        } else {
                            val newParams = updateListItemParameters(parameters, index)
                            val itemDfeSupplier: () -> DataFetchingEnvironment = { buildDataFetchingEnvironment(newParams, field, parameters.parentEngineResult) }
                            accessCheckRunner.typeCheck(newParams, itemDfeSupplier, oer, itemFieldResolutionResult, this)
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
            return FieldResolutionResult.fromFetchedValue(effectiveData, fetchedValue, effectiveResolutionPolicy, originalSource = effectiveData)
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
            return buildFieldResolutionResult(parameters, resolvedType, fetchedValue, effectiveResolutionPolicy)
        }
        // When it's an object, wrap the whole thing
        if (GraphQLTypeUtil.isObjectType(fieldType)) {
            val oer = if (fetchedValue.fetchedValue is LazyEngineObjectData) {
                ObjectEngineResultImpl.newPendingForType(fieldType as GraphQLObjectType)
            } else {
                ObjectEngineResultImpl.newForType(fieldType as GraphQLObjectType)
            }
            return FieldResolutionResult.fromFetchedValue(oer, fetchedValue, effectiveResolutionPolicy, originalSource = effectiveData)
        }
        throw IllegalStateException("ObjectEngineResult must wrap a GraphQLObjectType.")
    }

    private fun maybeFetchLazyData(
        result: FieldResolutionResult,
        outputType: GraphQLOutputType,
        parameters: ExecutionParameters,
        env: Supplier<DataFetchingEnvironment>
    ) {
        // if engineResult is null, then there's nothing to lazily fetch and we can return early
        if (result.engineResult == null) return
        when (outputType) {
            is GraphQLNonNull ->
                maybeFetchLazyData(result, GraphQLTypeUtil.unwrapOneAs(outputType), parameters, env)

            is GraphQLList -> {
                val engineResult = result.engineResult
                check(engineResult is Iterable<*>) { "Expected iterable engineResult but got $engineResult" }

                engineResult.forEach {
                    check(it is Cell) { "Expected Cell but got $it" }
                    val frr = extractFieldResolutionResult(it)
                    maybeFetchLazyData(frr, GraphQLTypeUtil.unwrapOneAs(outputType), parameters, env)
                }
            }

            else -> {
                val originalSource = result.originalSource
                if (originalSource !is LazyEngineObjectData) return

                val engineResult = checkNotNull(result.engineResult as? ObjectEngineResultImpl) {
                    "Expected ObjectEngineResultImpl but got ${result.engineResult}"
                }
                parameters.launchOnRootScope {
                    try {
                        val dataFetchingEnvironment = env.get()
                        val selections = parameters.engineExecutionContext.rawSelectionSetFactory.rawSelectionSet(dataFetchingEnvironment)
                            ?: throw IllegalStateException(
                                "Attempting to resolve LazyEngineObjectData but no selection set found"
                            )
                        originalSource.resolveData(selections, dataFetchingEnvironment.engineExecutionContext)
                        engineResult.resolve()
                    } catch (e: Exception) {
                        if (e is CancellationException) currentCoroutineContext().ensureActive()
                        engineResult.resolveExceptionally(e)
                    }
                }
            }
        }
    }

    private fun extractFieldResolutionResult(cell: Cell): FieldResolutionResult {
        val rawValue = cell.getValue(RAW_VALUE_SLOT)
        return when (rawValue) {
            is Value.Sync<*> -> {
                val result = rawValue.getOrThrow()
                result as? FieldResolutionResult ?: throw IllegalStateException("Expected FieldResolutionResult but got ${result!!::class}")
            }

            else -> throw IllegalStateException(
                "Expected the raw value slot to contain a Value.Sync<FieldResolutionResult>, but got ${rawValue::class}"
            )
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
                    val frr = extractFieldResolutionResult(item)
                    val newParams = updateListItemParameters(parameters, i)
                    maybeFetchNestedObject(frr, GraphQLTypeUtil.unwrapOneAs(outputType), field, newParams)
                }
                Value.waitAll(values)
            }

            else -> {
                // if engineResult is a scalar or simple value, then no nesting is possible and we can return
                val oer = fieldResolutionResult.engineResult as? ObjectEngineResultImpl ?: return Value.fromValue(Unit)
                fetchObject(
                    oer.graphQLObjectType,
                    parameters.forObjectTraversal(field, oer, fieldResolutionResult.localContext, fieldResolutionResult.originalSource, fieldResolutionResult.resolutionPolicy)
                )
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
     * @return [Value] of [FetchedValueWithExtensions]
     */
    private fun fetchField(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
    ): Pair<Value<FieldResolutionResult>, Value<out CheckerResult?>> =
        try {
            val fieldDef = parameters.executionStepInfo.fieldDefinition
            var dataFetcher = parameters.graphQLSchema.codeRegistry.getDataFetcher(
                FieldExecutionHelpers.coordinateOfField(parameters, field),
                fieldDef
            )

            val instrumentationFieldFetchParams = InstrumentationFieldFetchParameters(
                parameters.executionContextWithLocalContext,
                dataFetchingEnvironmentProvider,
                parameters.gjParameters,
                dataFetcher is TrivialDataFetcher<*>
            )
            val fieldFetchingInstCtx = parameters.instrumentation.beginFieldFetching(
                instrumentationFieldFetchParams,
                parameters.executionContext.instrumentationState
            ) ?: FieldFetchingInstrumentationContext.NOOP

            fieldFetchingInstCtx.onDispatched()

            // Instrument the data fetcher
            dataFetcher = parameters.instrumentation.instrumentDataFetcher(
                dataFetcher,
                instrumentationFieldFetchParams,
                parameters.executionContext.instrumentationState
            )

            // For top-level mutation and subscription fields, execute the data fetcher only if the access check succeeds.
            // For everything else, execute the access check in parallel with the data fetcher.
            val executeCheckerSequentially = when (parameters.executionStepInfo.objectType.name) {
                parameters.graphQLSchema.mutationType?.name,
                parameters.graphQLSchema.subscriptionType?.name -> true
                else -> false
            }

            val fieldType = parameters.executionStepInfo.unwrappedNonNullType
            val fieldCheckerResultValue = accessCheckRunner.fieldCheck(parameters, dataFetchingEnvironmentProvider)

            val dataFetcherResult = if (executeCheckerSequentially) {
                // In sequential mode, wait for field checker before executing data fetcher
                fieldCheckerResultValue.thenCompose { fieldCheckerRes, fieldCheckerError ->
                    if (fieldCheckerRes is CheckerResult.Error || fieldCheckerError != null) {
                        // The field checker has failed. Don't execute the data fetcher.
                        Value.nullValue
                    } else {
                        executeDataFetcher(parameters, fieldDef, dataFetchingEnvironmentProvider, dataFetcher)
                    }
                }
            } else {
                // In parallel mode, execute data fetcher immediately
                executeDataFetcher(parameters, fieldDef, dataFetchingEnvironmentProvider, dataFetcher)
            }
            val rawValue = dataFetcherResult.thenCompose { v, e -> dataFetcherResultToValue(field, parameters, v, e) }

            val result: Value<FieldResolutionResult> = rawValue
                .map { fv ->
                    buildFieldResolutionResult(parameters, fieldType, fv, parameters.resolutionPolicy)
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

            val combinedCheckerResult = accessCheckRunner.combineWithTypeCheck(
                parameters,
                dataFetchingEnvironmentProvider,
                fieldCheckerResultValue,
                fieldType,
                result,
                this
            )

            // Complete instrumentation exactly once. Data fetcher errors take priority over checker errors.
            // In sequential mode, field checker is checked first before executing the data fetcher.
            val completeFieldFetching = {
                dataFetcherResult.thenApply { dfValue, dataFetcherError ->
                    if (dataFetcherError != null) {
                        // Data fetcher failed - complete instrumentation immediately
                        fieldFetchingInstCtx.onCompleted(null, dataFetcherError)
                    } else {
                        // Data fetcher succeeded - wait for combined checker result
                        combinedCheckerResult.thenApply { res, throwable ->
                            fieldFetchingInstCtx.onCompleted(dfValue, res?.asError?.error ?: throwable)
                        }
                    }
                }
            }

            if (executeCheckerSequentially) {
                fieldCheckerResultValue.thenApply { fieldCheckerRes, fieldCheckerError ->
                    if (fieldCheckerRes is CheckerResult.Error || fieldCheckerError != null) {
                        // Field checker failed - complete instrumentation immediately (no DF executed, no type check)
                        fieldFetchingInstCtx.onCompleted(null, fieldCheckerRes?.asError?.error ?: fieldCheckerError)
                    } else {
                        // Field checker passed - now check data fetcher result
                        completeFieldFetching()
                    }
                }
            } else {
                // Parallel mode - check data fetcher result first
                completeFieldFetching()
            }

            result to combinedCheckerResult
        } catch (e: Exception) {
            val error = InternalEngineException.wrapWithPathAndLocation(e, parameters.path, field.sourceLocation)
            Value.fromThrowable<FieldResolutionResult>(error) to Value.fromThrowable(error)
        }

    /**
     * Converts the result of [executeDataFetcher] into Value<FetchedValueWithExtensions>.
     *
     * @param field The field that was fetched
     * @param parameters The execution parameters
     * @param value The result of executing the data fetcher
     * @param error Any exception from executing the data fetcher
     */
    private fun dataFetcherResultToValue(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        value: Any?,
        error: Throwable?
    ): Value<FetchedValueWithExtensions> {
        if (error != null) {
            // wrap the exception in a FieldFetchingException to disambiguate it from other exceptions
            return Value.fromThrowable(FieldFetchingException.wrapWithPathAndLocation(error, parameters.path, field.sourceLocation))
        }

        return Value.fromValue(toFetchedValueOrThrow(parameters, value))
    }

    /**
     * Converts a data fetcher result to [FetchedValueWithExtensions].
     *
     * Handles two input types:
     * - Raw values (String, List, Map, null, etc.) → wrapped in [FetchedValueWithExtensions]
     * - [DataFetcherResult] → extracts data/errors/extensions and wraps in [FetchedValueWithExtensions]
     *
     * @param parameters The execution parameters.
     * @param result The result from the data fetcher.
     * @return A [FetchedValueWithExtensions] containing the normalized result.
     * @throws IllegalStateException if result is already a [FetchedValueWithExtensions], as this indicates a double-wrapping bug.
     */
    private fun toFetchedValueOrThrow(
        parameters: ExecutionParameters,
        result: Any?,
    ): FetchedValueWithExtensions {
        check(result !is FetchedValueWithExtensions) {
            "Result is already a FetchedValueWithExtensions - this indicates a double-wrapping bug"
        }
        if (result !is DataFetcherResult<*>) {
            return FetchedValueWithExtensions(
                parameters.executionContext.valueUnboxer.unbox(result),
                mutableListOf(),
                parameters.localContext,
                emptyMap()
            )
        }
        val localContext = result.localContext?.let { result.compositeLocalContext }
            ?: parameters.localContext
        val value = parameters.executionContext.valueUnboxer.unbox(result.data)
        return FetchedValueWithExtensions(value, result.errors, localContext, result.extensions ?: emptyMap())
    }

    /**
     * Executes a data fetcher and handles both sync and async results.
     *
     * @param parameters The execution parameters
     * @param fieldDef The field definition
     * @param dataFetchingEnvironment The data fetching environment supplier
     * @param dataFetcher The data fetcher to execute
     * @return [Value] that describes the fetched data or error
     */
    private fun executeDataFetcher(
        parameters: ExecutionParameters,
        fieldDef: GraphQLFieldDefinition,
        dataFetchingEnvironment: Supplier<DataFetchingEnvironment>,
        dataFetcher: DataFetcher<*>,
    ): Value<Any?> =
        try {
            if (dataFetcher is LightDataFetcher) {
                dataFetcher.get(fieldDef, parameters.source, dataFetchingEnvironment)
            } else {
                dataFetcher.get(dataFetchingEnvironment.get())
            }.let { // Any? | CompletionStage<*>
                if (it is CompletionStage<*>) {
                    Value.fromDeferred(it.asDeferred())
                } else {
                    Value.fromValue(it)
                }
            }
        } catch (e: Exception) {
            Value.fromThrowable(e)
        }
}
