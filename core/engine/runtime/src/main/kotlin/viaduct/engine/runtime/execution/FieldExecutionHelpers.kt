package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.TrivialDataFetcher
import graphql.collect.ImmutableMapWithNullValues
import graphql.execution.CoercedVariables
import graphql.execution.DataFetcherResult
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStepInfo
import graphql.execution.ExecutionStepInfoFactory
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.MergedField
import graphql.execution.NormalizedVariables
import graphql.execution.RawVariables
import graphql.execution.ResultPath
import graphql.execution.ValuesResolver
import graphql.execution.directives.QueryDirectivesImpl
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.VariableDefinition
import graphql.normalized.ExecutableNormalizedField
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.DataFetchingFieldSelectionSetImpl
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLNamedType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.LightDataFetcher
import graphql.util.FpKit
import java.util.Locale
import java.util.concurrent.CompletionStage
import java.util.function.Supplier
import viaduct.deferred.asDeferred
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ParentManagedValue
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolutionPolicy
import viaduct.engine.api.StandardResolutionValue
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.gj
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldRssOriginFilteringKillSwitchEnabled
import viaduct.engine.runtime.EngineExecutionContextExtensions.isResolverSelective
import viaduct.engine.runtime.EngineExecutionContextExtensions.matResolutionEnabled
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.FetchedValueWithExtensions
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.exceptions.FieldFetchingException
import viaduct.engine.runtime.observability.ExecutionObservabilityContext
import viaduct.graphql.utils.ParsedSelections

internal fun QueryPlan.CollectedField.oerKey(
    arguments: Map<String, Any?>,
    selections: ObjectEngineResult.Selections? = null,
): ObjectEngineResult.Key =
    ObjectEngineResult.Key(
        name = fieldName,
        alias = alias,
        arguments = arguments,
        selectionSet = selections,
    )

object FieldExecutionHelpers {
    val executionStepInfoFactory = ExecutionStepInfoFactory()

    fun coordinateOfField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField
    ): FieldCoordinates {
        val objectType = parameters.executionStepInfo.objectType
        val fieldName = field.mergedField.name
        return (objectType.name to fieldName).gj
    }

    /** Builds the data fetcher and instrumentation parameters for [field]. */
    internal fun buildFieldDataFetcher(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        dataFetchingEnvironmentProvider: Supplier<DataFetchingEnvironment>,
    ): FieldDataFetcher {
        val fieldDefinition = parameters.executionStepInfo.fieldDefinition
        val dataFetcher = parameters.graphQLSchema.codeRegistry.getDataFetcher(
            coordinateOfField(parameters, field),
            fieldDefinition,
        )
        return FieldDataFetcher(
            fieldDefinition = fieldDefinition,
            dataFetcher = dataFetcher,
            instrumentationParameters = InstrumentationFieldFetchParameters(
                parameters.executionContextWithLocalContext,
                dataFetchingEnvironmentProvider,
                parameters.gjParameters,
                dataFetcher is TrivialDataFetcher<*>,
            ),
        )
    }

    /** Applies field instrumentation to the data fetcher. */
    internal fun instrumentDataFetcher(
        parameters: ExecutionParameters,
        fieldDataFetcher: FieldDataFetcher,
    ): DataFetcher<*> =
        parameters.instrumentation.instrumentDataFetcher(
            fieldDataFetcher.dataFetcher,
            fieldDataFetcher.instrumentationParameters,
            parameters.executionContext.instrumentationState,
        )

    /** Executes [dataFetcher] and wraps its result or failure in a [Value]. */
    internal fun executeDataFetcher(
        parameters: ExecutionParameters,
        fieldDefinition: GraphQLFieldDefinition,
        dataFetchingEnvironment: Supplier<DataFetchingEnvironment>,
        dataFetcher: DataFetcher<*>,
    ): Value<Any?> =
        try {
            if (dataFetcher is LightDataFetcher) {
                dataFetcher.get(fieldDefinition, parameters.source, dataFetchingEnvironment)
            } else {
                dataFetcher.get(dataFetchingEnvironment.get())
            }.let {
                if (it is CompletionStage<*>) {
                    Value.fromDeferred(it.asDeferred())
                } else {
                    Value.fromValue(it)
                }
            }
        } catch (e: Exception) {
            Value.fromThrowable(e)
        }

    /** Converts a data fetcher result or failure into a fetched-value [Value]. */
    internal fun dataFetcherResultToValue(
        field: QueryPlan.CollectedField,
        parameters: ExecutionParameters,
        value: Any?,
        error: Throwable?,
    ): Value<FetchedValueWithExtensions> {
        if (error != null) {
            return Value.fromThrowable(
                FieldFetchingException.wrapWithPathAndLocation(
                    error,
                    parameters.path,
                    field.sourceLocation,
                )
            )
        }

        return Value.fromValue(toFetchedValueOrThrow(parameters, value))
    }

    /** Converts [result] into a [FetchedValueWithExtensions]. */
    internal fun toFetchedValueOrThrow(
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
                emptyMap(),
            )
        }
        val localContext = result.localContext?.let { result.compositeLocalContext }
            ?: parameters.localContext
        val value = parameters.executionContext.valueUnboxer.unbox(result.data)
        return FetchedValueWithExtensions(value, result.errors, localContext, result.extensions ?: emptyMap())
    }

    /** Returns the underlying value and effective resolution policy for [data]. */
    internal fun unwrapResolutionValue(
        data: Any?,
        resolutionPolicy: ResolutionPolicy,
    ): UnwrappedResolutionValue =
        when (data) {
            is ParentManagedValue -> UnwrappedResolutionValue(data.value, ResolutionPolicy.PARENT_MANAGED)
            is StandardResolutionValue -> UnwrappedResolutionValue(data.value, ResolutionPolicy.STANDARD)
            else -> UnwrappedResolutionValue(data, resolutionPolicy)
        }

    /**
     * Builds the key for the [ObjectEngineResultImpl] for a given field.
     *
     * @param field The field for which to build the key.
     * @return The constructed key.
     */
    fun buildOERKeyForField(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField
    ): ObjectEngineResult.Key {
        val isResolverSelective = parameters.engineExecutionContext.isResolverSelective

        val runtimeResolverCoordinate = parameters.executionStepInfo.objectType.name to field.fieldName
        val includeSelectionsInKey = isResolverSelective(runtimeResolverCoordinate)

        val selectionSet = if (includeSelectionsInKey) {
            field.selectionSet?.let {
                ExecutionSelections(
                    parameters.graphQLSchema,
                    it,
                    parameters.queryPlan.fragments,
                    parameters.coercedVariables,
                    parameters.constants.collectCache,
                    parameters.engineExecutionContext.fieldRssOriginFilteringKillSwitchEnabled,
                )
            }
        } else {
            null
        }

        return field.oerKey(
            arguments = parameters.executionStepInfo.arguments,
            selections = selectionSet,
        )
    }

    internal fun engineSelectionSet(ctx: EngineExecutionContext): EngineSelectionSet? = engineSelectionSet(ctx.executionHandle!!.asExecutionParameters(), ctx)

    internal fun engineSelectionSet(parameters: ExecutionParameters): EngineSelectionSet? = engineSelectionSet(parameters, parameters.engineExecutionContext)

    private fun engineSelectionSet(
        parameters: ExecutionParameters,
        engineExecutionContext: EngineExecutionContext,
    ): EngineSelectionSet? {
        val field = requireNotNull(parameters.field)

        if (engineExecutionContext.matResolutionEnabled) {
            return ExecutionSelectionSet.createForField(parameters, field)
        }

        val unwrappedType = GraphQLTypeUtil.unwrapAll(parameters.executionStepInfo.type)
        if (unwrappedType !is GraphQLCompositeType) return null
        val typeName = (unwrappedType as GraphQLNamedType).name

        val selections = field.mergedField.fields.mapNotNull { it.selectionSet }
            .flatMap { it.selections }
            .let(::GJSelectionSet)
        val fieldScope = engineExecutionContext.fieldScope
        val variables = fieldScope.variables.ifEmpty { parameters.coercedVariables.toMap() }
        return engineExecutionContext.engineSelectionSetFactory.engineSelectionSet(
            ParsedSelections(
                typeName,
                selections,
                fieldScope.fragments,
            ),
            variables,
        )
    }

    /**
     * Builds a DataFetchingEnvironment for the given field execution.
     *
     * IMPORTANT: This creates a context-sensitive environment where fragments and variables
     * are set based on the current execution depth:
     * - During root operation execution: uses operation's fragments/variables from client query
     * - During child plan execution (RSS/variables resolver): uses child plan's fragments/variables
     *
     * This ensures code always has the correct execution context, whether resolving the root query
     * or executing a required selection set.
     */
    fun buildDataFetchingEnvironment(
        parameters: ExecutionParameters,
        field: QueryPlan.CollectedField,
        currentOER: ObjectEngineResultImpl,
    ): DataFetchingEnvironment {
        val mergedField = checkNotNull(field.mergedField) {
            "FieldExecutionHelpers.buildDataFetchingEnvironment requires a merged field"
        }
        val fieldDef = parameters.executionStepInfo.fieldDefinition
        val execStepInfoSupplier = { parameters.executionStepInfo }
        val argumentValuesSupplier = { parameters.executionStepInfo.arguments }
        val normalizedFieldSupplier = getNormalizedField(parameters.executionContext, parameters.gjParameters, execStepInfoSupplier)
        val normalizedVariableValuesSupplier = {
            // ViaductExecutionStrategy does not use NormalizedVariables, though the GJ interface requires them.
            NormalizedVariables.emptyVariables()
        }
        val fieldCollector = DataFetchingFieldSelectionSetImpl.newCollector(
            parameters.graphQLSchema,
            fieldDef.type,
            normalizedFieldSupplier,
        )
        val queryDirectives = QueryDirectivesImpl(
            mergedField,
            parameters.graphQLSchema,
            parameters.coercedVariables,
            normalizedVariableValuesSupplier,
            parameters.executionContext.graphQLContext,
            parameters.executionContext.locale
        )
        val fieldResolverMetadata = field.collectedFieldMetadata?.resolvedByCoordinate?.let {
            parameters.engineExecutionContext.dispatcherRegistry.getFieldResolverDispatcher(it.first, it.second)?.resolverMetadata
        }
        val localContext = parameters.localContext.let { ctx ->
            // update the context with either a new EngineResultLocalContext or update the existing one
            ctx.get<EngineResultLocalContext>().let { extant ->
                ctx.addOrUpdate(
                    // if the context is already set, just update the currentOER
                    extant?.copy(
                        currentObjectEngineResult = currentOER,
                        queryEngineResult = parameters.queryEngineResult,
                    ) ?: EngineResultLocalContext(
                        // otherwise create it
                        currentObjectEngineResult = currentOER,
                        queryEngineResult = parameters.queryEngineResult,
                        rootEngineResult = parameters.rootEngineResult,
                        executionStrategyParams = parameters.gjParameters,
                        executionContext = parameters.executionContext,
                    ),
                    ExecutionObservabilityContext(
                        resolverMetadata = fieldResolverMetadata,
                        attribution = parameters.queryPlan.attribution
                    )
                )
            }
        }

        val dfe = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(parameters.executionContext)
            .source(parameters.source)
            .localContext(localContext)
            .arguments(argumentValuesSupplier)
            .fieldDefinition(fieldDef)
            .mergedField(mergedField)
            .fieldType(fieldDef.type)
            .executionStepInfo(execStepInfoSupplier)
            .parentType(currentOER.type)
            .selectionSet(fieldCollector)
            .queryDirectives(queryDirectives)
            .build()

        // Give the DFE its own EEC copy so the wrapper can point back at this DFE
        // without mutating the ExecutionParameters-owned context.
        val updatedEngineExecCtx = parameters.engineExecutionContext.copy()
        return ViaductDataFetchingEnvironmentImpl(dfe, updatedEngineExecCtx)
    }

    internal fun createOERSelections(
        variables: CoercedVariables,
        engineExecutionContext: EngineExecutionContext,
        queryPlan: QueryPlan,
    ): ObjectEngineResult.Selections {
        val executionParameters = engineExecutionContext.executionHandle!!.asExecutionParameters()

        return ExecutionSelections(
            engineExecutionContext.fullSchema.schema,
            queryPlan.selectionSet,
            queryPlan.fragments,
            variables,
            executionParameters.constants.collectCache,
            engineExecutionContext.fieldRssOriginFilteringKillSwitchEnabled,
        )
    }

    internal fun findRssQueryPlan(
        rss: RequiredSelectionSet,
        engineExecutionContext: EngineExecutionContext,
    ): QueryPlan = findRssQueryPlan(rss.id, engineExecutionContext.executionHandle!!.asExecutionParameters())

    internal fun findRssQueryPlan(
        requiredSelectionSetId: RequiredSelectionSet.Id,
        executionParameters: ExecutionParameters,
    ): QueryPlan =
        checkNotNull(executionParameters.queryPlanIndex.find(requiredSelectionSetId)) {
            "Missing QueryPlan for RequiredSelectionSet $requiredSelectionSetId"
        }

    fun createExecutionStepInfo(
        codeRegistry: GraphQLCodeRegistry,
        executionContext: ExecutionContext,
        coercedVariables: CoercedVariables,
        field: MergedField,
        path: ResultPath,
        parentExecutionStepInfo: ExecutionStepInfo,
        fieldDefinition: GraphQLFieldDefinition,
        fieldContainer: GraphQLObjectType?,
    ): ExecutionStepInfo {
        val fieldType = fieldDefinition.type
        val arguments: Supplier<ImmutableMapWithNullValues<String, Any>> =
            if (fieldDefinition.arguments.isEmpty()) {
                Supplier { ImmutableMapWithNullValues.emptyMap() }
            } else {
                getArgumentValues(
                    codeRegistry,
                    executionContext,
                    coercedVariables,
                    fieldDefinition,
                    field,
                )
            }

        return ExecutionStepInfo.newExecutionStepInfo()
            .type(fieldType)
            .fieldDefinition(fieldDefinition)
            .fieldContainer(fieldContainer)
            .field(field)
            .path(path)
            .parentInfo(parentExecutionStepInfo)
            .arguments(arguments)
            .build()
    }

    internal fun resolveFieldArguments(
        codeRegistry: GraphQLCodeRegistry,
        fieldDefinition: GraphQLFieldDefinition,
        field: MergedField,
        coercedVariables: CoercedVariables,
        graphQLContext: GraphQLContext,
        locale: Locale,
    ): ImmutableMapWithNullValues<String, Any> =
        if (fieldDefinition.arguments.isEmpty()) {
            ImmutableMapWithNullValues.emptyMap()
        } else {
            ImmutableMapWithNullValues.copyOf(
                ValuesResolver.getArgumentValues(
                    codeRegistry,
                    fieldDefinition.arguments,
                    field.arguments,
                    coercedVariables,
                    graphQLContext,
                    locale,
                )
            )
        }

    private fun getArgumentValues(
        codeRegistry: GraphQLCodeRegistry,
        executionContext: ExecutionContext,
        coercedVariables: CoercedVariables,
        fieldDefinition: GraphQLFieldDefinition,
        field: MergedField,
    ): Supplier<ImmutableMapWithNullValues<String, Any>> =
        FpKit.intraThreadMemoize {
            resolveFieldArguments(
                codeRegistry,
                fieldDefinition,
                field,
                coercedVariables,
                executionContext.graphQLContext,
                executionContext.locale,
            )
        }

    private fun getNormalizedField(
        executionContext: ExecutionContext,
        parameters: ExecutionStrategyParameters,
        executionStepInfo: Supplier<ExecutionStepInfo>
    ): Supplier<ExecutableNormalizedField> {
        val normalizedQuery = executionContext.normalizedQueryTree
        return FpKit.intraThreadMemoize {
            normalizedQuery.get().getNormalizedField(
                parameters.field,
                executionStepInfo.get().objectType,
                executionStepInfo.get().path
            )
        }
    }

    /**
     * Run [CollectFields] for the given state
     * @param objectType the current concrete object
     * @param parameters the ExecutionParameters that contains the selection set and
     * variables to be collected
     */
    fun collectFields(
        objectType: GraphQLObjectType,
        parameters: ExecutionParameters
    ): QueryPlan.SelectionSet =
        parameters.constants.collectCache.collect(
            parameters.graphQLSchema,
            parameters.selectionSet,
            parameters.coercedVariables,
            objectType,
            parameters.queryPlan.fragments,
            fieldRssOriginFilteringKillSwitchEnabled =
                parameters.engineExecutionContext.fieldRssOriginFilteringKillSwitchEnabled,
        )

    /**
     * Resolves variables for a [QueryPlan].
     */
    suspend fun resolveVariables(
        plan: QueryPlan,
        arguments: Map<String, Any?>,
        currentEngineData: ObjectEngineResult,
        queryEngineData: ObjectEngineResult,
        engineExecutionContext: EngineExecutionContext,
        graphQLContext: GraphQLContext,
        locale: Locale,
        instrumentationContext: ResolverInstrumentationContext? = null,
    ): CoercedVariables =
        resolveVariables(
            variableDefinitions = plan.variableDefinitions,
            variablesResolvers = plan.variablesResolvers,
            arguments = arguments,
            currentEngineData = currentEngineData,
            queryEngineData = queryEngineData,
            engineExecutionContext = engineExecutionContext,
            graphQLContext = graphQLContext,
            locale = locale,
            instrumentationContext = instrumentationContext,
        )

    suspend fun resolveQueryPlanVariables(
        plan: QueryPlan,
        arguments: Map<String, Any?>,
        currentEngineData: ObjectEngineResult,
        queryEngineData: ObjectEngineResult,
        engineExecutionContext: EngineExecutionContext,
        graphQLContext: GraphQLContext,
        locale: Locale,
    ): CoercedVariables =
        resolveVariables(
            plan,
            arguments,
            currentEngineData,
            queryEngineData,
            engineExecutionContext,
            graphQLContext,
            locale,
        )

    suspend fun resolveRSSVariables(
        arguments: Map<String, Any?>,
        currentEngineData: ObjectEngineResult,
        queryEngineData: ObjectEngineResult,
        engineExecutionContext: EngineExecutionContext,
        graphQLContext: GraphQLContext,
        locale: Locale,
        queryPlan: QueryPlan,
    ): CoercedVariables =
        resolveVariables(
            queryPlan,
            arguments,
            currentEngineData,
            queryEngineData,
            engineExecutionContext,
            graphQLContext,
            locale,
        )

    /**
     * Recursively resolve all values in the provided [variablesResolvers].
     * If any resolver in [variablesResolvers] depends on engine data, then this will return
     * after the dependee data have resolved.
     */
    private suspend fun resolveVariables(
        variableDefinitions: List<VariableDefinition>,
        variablesResolvers: List<VariablesResolver>,
        arguments: Map<String, Any?>,
        currentEngineData: ObjectEngineResult,
        queryEngineData: ObjectEngineResult,
        engineExecutionContext: EngineExecutionContext,
        graphQLContext: GraphQLContext,
        locale: Locale,
        instrumentationContext: ResolverInstrumentationContext? = null,
    ): CoercedVariables =
        variablesResolvers.fold(emptyMap<String, Any?>()) { acc, vr ->
            val isResolverSelective = engineExecutionContext.isResolverSelective
            val variablesData: EngineObjectData.Sync = vr.requiredSelectionSet?.let { vrss ->
                val childPlan = findRssQueryPlan(vrss, engineExecutionContext)
                // VariablesResolvers may have required selection sets which have their own variables resolvers.
                // Recursively resolve them
                val innerVariables = resolveVariables(
                    childPlan,
                    arguments,
                    currentEngineData,
                    queryEngineData,
                    engineExecutionContext,
                    graphQLContext,
                    locale,
                    instrumentationContext,
                )
                val vss = engineExecutionContext.engineSelectionSetFactory.engineSelectionSet(
                    vrss.selections,
                    variables = innerVariables.toMap()
                )
                val selectionContext = createOERSelections(
                    innerVariables,
                    engineExecutionContext,
                    childPlan,
                )

                val engineResult = if (vrss.selections.typeName == engineExecutionContext.fullSchema.schema.queryType.name) {
                    queryEngineData
                } else {
                    assert(currentEngineData.type.name == vrss.selections.typeName) {
                        "Expected current engine data type to match variable resolver selection set type `${vrss.selections.typeName}`, but instead found `${currentEngineData.type.name}`"
                    }
                    currentEngineData
                }
                SyncEngineObjectDataFactory.resolve(
                    engineResult,
                    "missing from variable RSS",
                    vss,
                    isResolverSelective = isResolverSelective,
                    selections = selectionContext,
                    skipAccessCheck = vrss.forChecker,
                    instrumentationContext = instrumentationContext,
                )
            } ?: SyncEngineObjectDataFactory.resolve(
                currentEngineData,
                "missing from variable RSS",
                isResolverSelective = isResolverSelective,
                instrumentationContext = instrumentationContext,
            )

            val resolved = vr.resolve(VariablesResolver.ResolveCtx(variablesData, arguments), engineExecutionContext)
            acc + resolved
        }.let {
            ValuesResolver.coerceVariableValues(
                engineExecutionContext.fullSchema.schema,
                variableDefinitions,
                RawVariables(it),
                graphQLContext,
                locale
            )
        }
}

/** Holds a data fetcher and the metadata needed to execute and instrument it. */
internal data class FieldDataFetcher(
    val fieldDefinition: GraphQLFieldDefinition,
    val dataFetcher: DataFetcher<*>,
    val instrumentationParameters: InstrumentationFieldFetchParameters,
)

/** Holds an unwrapped resolver value and its effective resolution policy. */
internal data class UnwrappedResolutionValue(
    val value: Any?,
    val resolutionPolicy: ResolutionPolicy,
)
