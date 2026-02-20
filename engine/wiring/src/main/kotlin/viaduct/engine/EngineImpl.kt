@file:Suppress("DEPRECATION")

package viaduct.engine

import graphql.ExecutionInput as GJExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
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
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.SubqueryExecutionException
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.AccessCheckRunner
import viaduct.engine.runtime.execution.ExecutionParameters
import viaduct.engine.runtime.execution.FieldCompleter
import viaduct.engine.runtime.execution.FieldExecutionHelpers
import viaduct.engine.runtime.execution.FieldResolver
import viaduct.engine.runtime.execution.QueryPlan
import viaduct.engine.runtime.execution.ViaductExecutionStrategy
import viaduct.engine.runtime.execution.WrappedCoroutineExecutionStrategy
import viaduct.engine.runtime.execution.asExecutionParameters
import viaduct.engine.runtime.graphql_java.GraphQLJavaConfig
import viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation
import viaduct.engine.runtime.instrumentation.TaggedMetricInstrumentation
import viaduct.engine.runtime.select.EngineSelectionSetImpl
import viaduct.service.api.spi.FlagManager

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
) : Engine, EngineGraphQLJavaCompat {
    private val coroutineInterop: CoroutineInterop = config.coroutineInterop
    private val flagManager: FlagManager = config.flagManager
    private val temporaryBypassAccessCheck: TemporaryBypassAccessCheck = config.temporaryBypassAccessCheck
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler = config.dataFetcherExceptionHandler
    private val meterRegistry: MeterRegistry? = config.meterRegistry
    private val additionalInstrumentation: Instrumentation? = config.additionalInstrumentation

    private val resolverDataFetcherInstrumentation = ResolverDataFetcherInstrumentation(
        dispatcherRegistry,
        flagManager,
        config.resolverInstrumentation,
        coroutineInterop
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

    private val fieldResolver = FieldResolver(accessCheckRunner)

    private val fieldCompleter = FieldCompleter(dataFetcherExceptionHandler, temporaryBypassAccessCheck)

    private val viaductExecutionStrategyFactory =
        ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler,
            ExecutionParameters.Factory(
                flagManager
            ),
            accessCheckRunner,
            coroutineInterop,
            temporaryBypassAccessCheck
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
    ): EngineObjectData {
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

        val rssImpl = selectionSet as EngineSelectionSetImpl

        val eecImpl = parentParams.engineExecutionContext as EngineExecutionContextImpl

        val selectionParams = try {
            val queryPlan = QueryPlan.buildFromSelections(
                parameters = eecImpl.queryPlanParameters(),
                rss = selectionSet,
            )
            parentParams.forChildPlan(
                queryPlan,
                rssImpl.ctx.coercedVariables,
                ExecutionParameters.ChildPlanTarget.WithOER(targetOER),
            )
        } catch (e: Exception) {
            throw SubqueryExecutionException.queryPlanBuildFailed(e)
        }

        try {
            when (options.operationType) {
                Engine.OperationType.QUERY -> fieldResolver.fetchObject(rootType, selectionParams)
                Engine.OperationType.MUTATION -> fieldResolver.fetchObjectSerially(rootType, selectionParams)
            }.await()
        } catch (e: Exception) {
            throw SubqueryExecutionException.fieldResolutionFailed(e)
        }

        return ProxyEngineObjectData(
            targetOER,
            "add it to the selection set provided to Context.${options.operationType.name.lowercase()}() in order to access it from the result",
            selectionSet
        )
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

        // 2. Resolve RSS variables from the parent's engine data
        val variables = FieldExecutionHelpers.resolveRSSVariables(
            rss = selectionSet,
            arguments = arguments,
            currentEngineData = parentParams.parentEngineResult,
            queryEngineData = parentParams.queryEngineResult,
            engineExecutionContext = parentParams.engineExecutionContext,
            graphQLContext = parentParams.executionContext.graphQLContext,
            locale = parentParams.executionContext.locale,
        )

        // 3. Build QueryPlan and child ExecutionParameters
        val eecImpl = parentParams.engineExecutionContext as EngineExecutionContextImpl

        val childParams = try {
            val queryPlan = QueryPlan.buildFromParsedSelections(
                parameters = eecImpl.queryPlanParameters(),
                parsedSelections = selectionSet.selections,
                attribution = selectionSet.attribution,
                executionCondition = selectionSet.executionCondition,
            )

            val target = if (options.isFieldTypePlan) {
                checkNotNull(targetOER) { "targetResult is required when isFieldTypePlan is true" }
                ExecutionParameters.ChildPlanTarget.FieldType(targetOER, parentParams.source)
            } else if (targetOER != null) {
                ExecutionParameters.ChildPlanTarget.WithOER(targetOER)
            } else {
                ExecutionParameters.ChildPlanTarget.FromContext
            }

            parentParams.forChildPlan(queryPlan, variables, target)
                .copy(bypassChecksDuringCompletion = options.bypassAccessChecks)
        } catch (e: Exception) {
            throw SubqueryExecutionException.queryPlanBuildFailed(e)
        }

        // 4. Complete and build ExecutionResult
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
            executeAccessChecksInModstrat = executeAccessChecksInModstrat,
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
