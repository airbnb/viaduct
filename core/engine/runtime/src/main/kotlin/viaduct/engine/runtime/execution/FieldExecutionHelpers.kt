package viaduct.engine.runtime.execution

import graphql.GraphQLContext
import graphql.collect.ImmutableMapWithNullValues
import graphql.execution.CoercedVariables
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
import graphql.language.Argument
import graphql.language.VariableDefinition
import graphql.normalized.ExecutableNormalizedField
import graphql.schema.DataFetchingEnvironment
import graphql.schema.DataFetchingEnvironmentImpl
import graphql.schema.DataFetchingFieldSelectionSetImpl
import graphql.schema.FieldCoordinates
import graphql.schema.GraphQLArgument
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.util.FpKit
import java.util.Locale
import java.util.function.Supplier
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.gj
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextExtensions.fieldRssOriginFilteringKillSwitchEnabled
import viaduct.engine.runtime.EngineExecutionContextExtensions.isResolverSelective
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineResultLocalContext
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.observability.ExecutionObservabilityContext

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

        return ObjectEngineResult.Key(
            field.fieldName,
            field.alias,
            parameters.executionStepInfo.arguments,
            selectionSet
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

        // Get the EngineExecutionContext from local context and update it with
        // context-sensitive field scope (fragments/variables)
        val fieldScope = FpKit.intraThreadMemoize {
            EngineExecutionContextImpl.FieldExecutionScopeImpl(
                fragments = parameters.queryPlan.fragments.map.mapValues { it.value.gjDef },
                variables = parameters.coercedVariables.toMap(),
                resolutionPolicy = parameters.resolutionPolicy,
                attribution = parameters.queryPlan.attribution ?: ExecutionAttribution.DEFAULT,
            )
        }
        val updatedEngineExecCtx = parameters.engineExecutionContext.copy(fieldScopeSupplier = fieldScope)
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

        return ExecutionStepInfo.newExecutionStepInfo()
            .type(fieldType)
            .fieldDefinition(fieldDefinition)
            .fieldContainer(fieldContainer)
            .field(field)
            .path(path)
            .parentInfo(parentExecutionStepInfo)
            .arguments {
                if (fieldDefinition.arguments.isNotEmpty()) {
                    val v = getArgumentValues(
                        codeRegistry,
                        executionContext,
                        coercedVariables,
                        fieldDefinition.arguments,
                        field.arguments
                    ).get()
                    ImmutableMapWithNullValues.copyOf(v)
                } else {
                    ImmutableMapWithNullValues.emptyMap()
                }
            }
            .build()
    }

    private fun getArgumentValues(
        codeRegistry: GraphQLCodeRegistry,
        executionContext: ExecutionContext,
        coercedVariables: CoercedVariables,
        argDefs: List<GraphQLArgument>,
        args: List<Argument>
    ): Supplier<ImmutableMapWithNullValues<String, Any>> {
        val argValuesSupplier = Supplier {
            val resolvedValues = ValuesResolver.getArgumentValues(
                codeRegistry,
                argDefs,
                args,
                coercedVariables,
                executionContext.graphQLContext,
                executionContext.locale
            )
            ImmutableMapWithNullValues.copyOf(resolvedValues)
        }
        return FpKit.intraThreadMemoize(argValuesSupplier)
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
