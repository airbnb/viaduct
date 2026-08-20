@file:Suppress("DEPRECATION")

package viaduct.engine

import graphql.ExecutionInput as GJExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.CoercedVariables
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionId
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.preparsed.PreparsedDocumentProvider
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLObjectType
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.future.await
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolveRootFieldReferenceOptions
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.SubqueryExecutionException
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.spi.CoroutineInterop
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.SelectionSetCompletionEngine
import viaduct.engine.runtime.SubqueryInstrumentationEngine
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.AccessCheckRunner
import viaduct.engine.runtime.execution.ChildQueryPlanTarget
import viaduct.engine.runtime.execution.ExecutionParameters
import viaduct.engine.runtime.execution.FieldCompleter
import viaduct.engine.runtime.execution.FieldExecutionHelpers
import viaduct.engine.runtime.execution.FieldResolver
import viaduct.engine.runtime.execution.QueryPlan
import viaduct.engine.runtime.execution.QueryPlanFactory
import viaduct.engine.runtime.execution.ViaductExecutionStrategy
import viaduct.engine.runtime.execution.WrappedCoroutineExecutionStrategy
import viaduct.engine.runtime.execution.asExecutionParameters
import viaduct.engine.runtime.fetchFieldResultForResolver
import viaduct.engine.runtime.graphql_java.GraphQLJavaConfig
import viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation
import viaduct.engine.runtime.instrumentation.TaggedMetricInstrumentation
import viaduct.service.api.spi.FlagManager
import viaduct.utils.string.sha256Hash

@Deprecated("Airbnb use only")
interface EngineGraphQLJavaCompat {
    fun getGraphQL(): GraphQL
}

@Suppress("DEPRECATION")
class EngineImpl(
    private val config: EngineConfiguration,
    dispatcherRegistry: DispatcherRegistry,
    override val schema: ViaductSchema,
    documentProvider: PreparsedDocumentProvider,
    private val fullSchema: ViaductSchema,
    private val queryPlanFactory: QueryPlanFactory,
) : Engine, EngineGraphQLJavaCompat, SubqueryInstrumentationEngine, SelectionSetCompletionEngine {
    private val coroutineInterop: CoroutineInterop = config.coroutineInterop
    private val airbnbBypassPolicyCheckDuringCompletion: Boolean = config.airbnbBypassPolicyCheckDuringCompletion
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler = config.dataFetcherExceptionHandler
    private val meterRegistry: MeterRegistry? = config.meterRegistry
    private val additionalInstrumentation: Instrumentation? = config.additionalInstrumentation
    private val flagManager: FlagManager = config.flagManager

    private val resolverDataFetcherInstrumentation = ResolverDataFetcherInstrumentation(
        dispatcherRegistry,
        config.resolverInstrumentation,
        coroutineInterop,
        config.tenantNameResolver
    )

    private val instrumentation = run {
        val taggedMetricInstrumentation = meterRegistry?.let {
            TaggedMetricInstrumentation(meterRegistry = it)
        }

        val scopeInstrumentation = ScopeInstrumentation()

        val defaultInstrumentations = listOfNotNull(
            scopeInstrumentation.asStandardInstrumentation,
            resolverDataFetcherInstrumentation,
            taggedMetricInstrumentation?.asStandardInstrumentation
        )
        if (config.chainInstrumentationWithDefaults) {
            val gjInstrumentation = additionalInstrumentation?.let {
                it as? ViaductModernGJInstrumentation ?: ViaductModernGJInstrumentation.fromStandardInstrumentation(it)
            }
            ChainedModernGJInstrumentation(defaultInstrumentations + listOfNotNull(gjInstrumentation))
        } else {
            additionalInstrumentation ?: ChainedModernGJInstrumentation(defaultInstrumentations)
        }
    }

    private val accessCheckRunner = AccessCheckRunner(coroutineInterop)

    private val fieldResolver = FieldResolver(accessCheckRunner, coroutineInterop)

    private val fieldCompleter = FieldCompleter(dataFetcherExceptionHandler, airbnbBypassPolicyCheckDuringCompletion)

    private val viaductExecutionStrategyFactory =
        ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler,
            ExecutionParameters.Factory(
                queryPlanFactory,
            ),
            accessCheckRunner,
            coroutineInterop,
            airbnbBypassPolicyCheckDuringCompletion
        )

    private val queryExecutionStrategy = WrappedCoroutineExecutionStrategy(
        viaductExecutionStrategyFactory.create(isSerial = false),
        coroutineInterop,
        dataFetcherExceptionHandler
    )

    private val mutationExecutionStrategy = WrappedCoroutineExecutionStrategy(
        viaductExecutionStrategyFactory.create(isSerial = true),
        coroutineInterop,
        dataFetcherExceptionHandler
    )

    private val subscriptionExecutionStrategy = WrappedCoroutineExecutionStrategy(
        viaductExecutionStrategyFactory.create(isSerial = true),
        coroutineInterop,
        dataFetcherExceptionHandler
    )

    private val graphql = GraphQL.newGraphQL(schema.schema)
        .preparsedDocumentProvider(IntrospectionRestrictingPreparsedDocumentProvider(documentProvider))
        .queryExecutionStrategy(queryExecutionStrategy)
        .mutationExecutionStrategy(mutationExecutionStrategy)
        .subscriptionExecutionStrategy(subscriptionExecutionStrategy)
        .instrumentation(instrumentation)
        .build()

    private val engineExecutionContextFactory = EngineExecutionContextFactory(
        fullSchema,
        dispatcherRegistry,
        resolverDataFetcherInstrumentation,
        flagManager,
        this,
        config.globalIDCodec,
        meterRegistry,
        config.fieldSelectivityProvider,
    )

    @Deprecated("Airbnb use only")
    override fun getGraphQL(): GraphQL {
        return graphql
    }

    override suspend fun execute(executionInput: ExecutionInput): ExecutionResult {
        val gjExecutionInput = mkGJExecutionInput(executionInput)
        return graphql.executeAsync(gjExecutionInput).await()
    }

    override suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync = resolveSelectionSet(executionHandle, selectionSet, options, instrumentationContext = null)

    override suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
        instrumentationContext: ResolverInstrumentationContext?,
    ): EngineObjectData.Sync {
        val subqueryExecution = executeSelectionSet(executionHandle, selectionSet, options)

        val errorMessage = "add it to the selection set provided to Context.${options.operationType.name.lowercase()}() in order to access it from the result"

        return SyncEngineObjectDataFactory.resolve(
            objectEngineResult = subqueryExecution.targetOER,
            errorMessage = errorMessage,
            selectionSet = selectionSet,
            instrumentationContext = instrumentationContext,
        )
    }

    override suspend fun resolveRootFieldReference(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        rootFieldPath: List<String>,
        arguments: Map<String, Any?>,
        selectionSet: EngineSelectionSet,
        options: ResolveRootFieldReferenceOptions,
    ): EngineObjectData? {
        require(rootFieldPath.isNotEmpty()) { "rootFieldPath must not be empty" }
        val parentParams = executionHandle.asExecutionParameters()
        val namespacePrefix = rootFieldPath.dropLast(1)
        // Reuse the request's Query result so references with the same namespace share execution.
        val namespaceParentResult = materializeNamespacePrefix(
            executionHandle = executionHandle,
            parentParams = parentParams,
            namespacePrefix = namespacePrefix,
            attribution = options.attribution,
        )
        val variables = selectionSet.variables.toMutableMap()
        val leafArguments = arguments.toSortedMap().entries.joinToString(", ") { (argumentName, value) ->
            var variableName = "__rfr_$argumentName"
            while (variableName in variables) {
                variableName += "_"
            }
            variables[variableName] = value
            "$argumentName: \$$variableName"
        }
        val argumentList = leafArguments.takeIf(String::isNotEmpty)?.let { "($it)" }.orEmpty()
        val leafSelectionsText = selectionSet.printAsFieldSet()
        // Child selections choose the selective resolver implementation, but are not resolved here.
        val leafSelection = parentParams.engineExecutionContext.engineSelectionSetFactory.engineSelectionSet(
            namespaceParentResult.type.name,
            "_rfr_${leafSelectionsText.sha256Hash()}: " +
                "${rootFieldPath.last()}$argumentList { $leafSelectionsText }",
            variables,
        )

        val leafRootParams = parametersForSelectionSet(
            parentParams = parentParams,
            selectionSet = leafSelection,
            attribution = options.attribution,
            target = ChildQueryPlanTarget.ExplicitObjectResult(namespaceParentResult),
        )
        val leafFieldPlan = FieldExecutionHelpers.collectFields(namespaceParentResult.type, leafRootParams)
            .selections
            .single() as QueryPlan.CollectedField
        val leafParams = leafRootParams.forField(namespaceParentResult.type, leafFieldPlan)
        // Keep this shallow so the caller can resolve nested fields from the resolver's original object.
        val result = fieldResolver.resolveShallowFieldResult(
            parameters = leafParams,
            field = leafFieldPlan,
        )
        val source = result.originalSource ?: return null
        check(source is EngineObjectData) {
            "Expected object resolver to return EngineObjectData, found ${source::class.simpleName}"
        }
        return source
    }

    /**
     * Executes [namespacePrefix] into the active Query result and returns the OER that owns the
     * referenced leaf field.
     *
     * Passing the existing Query OER to [resolveSelectionSet] preserves normal namespace execution
     * and memoizes shared prefixes across root field references.
     */
    private suspend fun materializeNamespacePrefix(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        parentParams: ExecutionParameters,
        namespacePrefix: List<String>,
        attribution: ExecutionAttribution,
    ): ObjectEngineResultImpl {
        if (namespacePrefix.isEmpty()) {
            return parentParams.queryEngineResult
        }

        val nestedSelection = namespacePrefix.asReversed().fold("__typename") { childSelection, fieldName ->
            "$fieldName { $childSelection }"
        }
        val prefixSelectionSet = parentParams.engineExecutionContext.engineSelectionSetFactory.engineSelectionSet(
            fullSchema.schema.queryType.name,
            nestedSelection,
            emptyMap(),
        )
        resolveSelectionSet(
            executionHandle = executionHandle,
            selectionSet = prefixSelectionSet,
            options = ResolveSelectionSetOptions(
                targetResult = parentParams.queryEngineResult,
                attribution = attribution,
            ),
        )
        return parentParams.queryEngineResult.requireNamespaceResult(namespacePrefix)
    }

    /**
     * Traverses already-materialized object field results along [path].
     *
     * Resolver and access-check errors are surfaced at the segment that produced them, and every
     * segment must resolve to an object because root-reference prefixes contain namespace fields
     * only.
     */
    private suspend fun ObjectEngineResultImpl.requireNamespaceResult(path: List<String>): ObjectEngineResultImpl {
        var current = this
        path.forEach { fieldName ->
            val fieldResult = current.fetchFieldResultForResolver(
                ObjectEngineResult.Key(fieldName),
                fieldDirectives = null,
            )
            current = fieldResult.engineResult as? ObjectEngineResultImpl
                ?: throw SubqueryExecutionException(
                    "Root field reference namespace `$fieldName` did not resolve to an object"
                )
        }
        return current
    }

    /**
     * Shared implementation that validates inputs, builds the query plan, and executes
     * field resolution. Returns the populated OER and execution parameters.
     *
     * This is the common preamble for resolveSelectionSet.
     */
    private suspend fun executeSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): SubqueryExecution {
        val parentParams = executionHandle.asExecutionParameters()

        // Determine root type from operation type
        val rootType: GraphQLObjectType = when (options.operationType) {
            Engine.OperationType.QUERY -> fullSchema.schema.queryType
            Engine.OperationType.MUTATION ->
                fullSchema.schema.mutationType
                    ?: throw SubqueryExecutionException("Schema does not have a mutation type")
        }

        if (selectionSet.type != rootType.name) {
            throw SubqueryExecutionException(
                "Cannot execute selections with type ${selectionSet.type} on schema root type ${rootType.name}"
            )
        }

        val targetOER = when (val result = options.targetResult) {
            null -> ObjectEngineResultImpl.newForType(rootType)
            is ObjectEngineResultImpl -> result
            else -> throw SubqueryExecutionException(
                "targetResult must be an ObjectEngineResultImpl, got ${result::class.simpleName}"
            )
        }

        // Mutation selection executions have a Mutation root result, but querySelections inside
        // them still execute against Query. Keep that Query result isolated from the parent request
        // and sibling subqueries.
        val queryOER = when (options.operationType) {
            Engine.OperationType.QUERY -> targetOER
            Engine.OperationType.MUTATION -> ObjectEngineResultImpl.newForType(fullSchema.schema.queryType)
        }
        val selectionParams = parametersForSelectionSet(
            parentParams = parentParams,
            selectionSet = selectionSet,
            attribution = options.attribution,
            target = ChildQueryPlanTarget.IsolatedRootResults(
                rootResult = targetOER,
                queryResult = queryOER,
            ),
        )

        try {
            val serialDispatch = options.operationType == Engine.OperationType.MUTATION
            fieldResolver.fetchObject(rootType, selectionParams, serialDispatch = serialDispatch).await()
        } catch (e: Exception) {
            throw SubqueryExecutionException.fieldResolutionFailed(e)
        }

        return SubqueryExecution(targetOER)
    }

    private data class SubqueryExecution(val targetOER: ObjectEngineResultImpl)

    /** Builds child execution parameters for an executable [selectionSet]. */
    private suspend fun parametersForSelectionSet(
        parentParams: ExecutionParameters,
        selectionSet: EngineSelectionSet,
        attribution: ExecutionAttribution,
        target: ChildQueryPlanTarget,
    ): ExecutionParameters =
        try {
            val queryPlan = queryPlanFactory.buildFromSelections(
                parameters = (parentParams.engineExecutionContext as EngineExecutionContextImpl).queryPlanParameters(),
                rss = selectionSet,
                attribution = attribution,
            )
            parentParams.forChildPlan(
                childPlan = queryPlan,
                variables = CoercedVariables.of(selectionSet.variables),
                target = target,
            )
        } catch (e: Exception) {
            throw SubqueryExecutionException.queryPlanBuildFailed(e)
        }

    override suspend fun completeSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: RequiredSelectionSet,
        targetResult: ObjectEngineResult?,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions,
    ): ExecutionResult {
        val parentParams = executionHandle.asExecutionParameters()

        // 1. Validate and extract targetOER
        val targetOER: ObjectEngineResultImpl? = when (targetResult) {
            null -> null
            is ObjectEngineResultImpl -> targetResult
            else -> throw SubqueryExecutionException(
                "targetResult must be an ObjectEngineResultImpl, got ${targetResult::class.simpleName}"
            )
        }

        // Validate type compatibility when an explicit OER is provided
        if (targetOER != null) {
            val rssTypeName = selectionSet.selections.typeName
            val oerType = targetOER.type
            val rssType = fullSchema.schema.getType(rssTypeName)
            val compatible = when (rssType) {
                is GraphQLObjectType -> rssType.name == oerType.name
                is GraphQLCompositeType -> fullSchema.schema.isPossibleType(rssType, oerType)
                else -> true
            }
            if (!compatible) {
                throw SubqueryExecutionException(
                    "Selection set type '$rssTypeName' is not compatible with " +
                        "target result type '${oerType.name}'"
                )
            }
        }

        // 2. Build QueryPlan and child ExecutionParameters
        val eecImpl = parentParams.engineExecutionContext as EngineExecutionContextImpl

        val childParams = try {
            val queryPlan = queryPlanFactory.buildFromRequiredSelectionSet(
                parameters = eecImpl.queryPlanParameters(),
                rss = selectionSet,
            )
            val parentParamsWithQueryPlanIndex = parentParams.withQueryPlanIndex(
                parentParams.queryPlanIndex + queryPlan.index
            )

            val variables = FieldExecutionHelpers.resolveRSSVariables(
                arguments = arguments,
                currentEngineData = parentParamsWithQueryPlanIndex.currentObjectEngineResult,
                queryEngineData = parentParamsWithQueryPlanIndex.queryEngineResult,
                engineExecutionContext = parentParamsWithQueryPlanIndex.engineExecutionContext,
                graphQLContext = parentParamsWithQueryPlanIndex.executionContext.graphQLContext,
                locale = parentParamsWithQueryPlanIndex.executionContext.locale,
                queryPlan = queryPlan
            )

            val target = if (options.isFieldTypePlan) {
                checkNotNull(targetOER) { "targetResult is required when isFieldTypePlan is true" }
                ChildQueryPlanTarget.ResolvedFieldObjectResult(targetOER, parentParamsWithQueryPlanIndex.source)
            } else if (targetOER != null) {
                ChildQueryPlanTarget.ExplicitObjectResult(targetOER)
            } else {
                parentParamsWithQueryPlanIndex.targetForChildPlan(queryPlan)
            }

            parentParamsWithQueryPlanIndex.forChildPlan(queryPlan, variables, target)
                .copy(bypassChecksDuringCompletion = options.bypassAccessChecks)
        } catch (e: Exception) {
            throw SubqueryExecutionException.queryPlanBuildFailed(e)
        }

        // 3. Complete and build ExecutionResult
        val completionResult = runCatching {
            fieldCompleter.completeObject(childParams).await()
        }
        return ViaductExecutionStrategy.buildExecutionResult(
            completionResult,
            childParams.errorAccumulator.toList()
        )
    }

    /**
     * This function is used to create the GraphQL-Java ExecutionInput that is needed to run the engine of GraphQL.
     *
     * @param executionInput The ExecutionInput object that has the data to create the input for execution
     *
     * @return GJExecutionInput created via the data inside the executionInput.
     */
    private fun EngineExecutionContextImpl.queryPlanParameters() =
        QueryPlan.Parameters(
            schema = fullSchema,
            registry = dispatcherRegistry,
            dispatcherRegistry = dispatcherRegistry,
        )

    private fun mkGJExecutionInput(executionInput: ExecutionInput): GJExecutionInput {
        val executionInputBuilder =
            GJExecutionInput
                .newExecutionInput()
                .executionId(ExecutionId.generate())
                .query(executionInput.operationText)

        executionInput.operationName?.let { executionInputBuilder.operationName(it) }
        executionInputBuilder.variables(executionInput.variables)
        val localContext = CompositeLocalContext.withContexts(createEngineExecutionContext(executionInput.requestContext))

        @Suppress("DEPRECATION")
        return executionInputBuilder
            .apply { executionInput.requestContext?.let { context(it) } }
            .localContext(localContext)
            .graphQLContext(GraphQLJavaConfig.default.asMap())
            .build()
    }

    /**
     * Creates an instance of EngineExecutionContext. This should be called exactly once
     * per request and set in the graphql-java execution input's local context.
     */
    fun createEngineExecutionContext(requestContext: Any?): EngineExecutionContext {
        return engineExecutionContextFactory.create(schema, requestContext)
    }
}
