package viaduct.engine

import graphql.ExecutionInput as GJExecutionInput
import graphql.GraphQL
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionId
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.preparsed.PreparsedDocumentProvider
import io.micrometer.core.instrument.MeterRegistry
import viaduct.deferred.asDeferred
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineExecutionResult
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.execution.AccessCheckRunner
import viaduct.engine.runtime.execution.ExecutionParameters
import viaduct.engine.runtime.execution.ViaductExecutionStrategy
import viaduct.engine.runtime.execution.WrappedCoroutineExecutionStrategy
import viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation
import viaduct.engine.runtime.instrumentation.TaggedMetricInstrumentation
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
    fullSchema: ViaductSchema,
) : Engine, EngineGraphQLJavaCompat {
    private val coroutineInterop: CoroutineInterop = config.coroutineInterop
    private val fragmentLoader: FragmentLoader = config.fragmentLoader
    private val flagManager: FlagManager = config.flagManager
    private val temporaryBypassAccessCheck: viaduct.engine.api.TemporaryBypassAccessCheck = config.temporaryBypassAccessCheck
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler = config.dataFetcherExceptionHandler
    private val meterRegistry: MeterRegistry? = config.meterRegistry
    private val additionalInstrumentation: Instrumentation? = config.additionalInstrumentation

    private val resolverDataFetcherInstrumentation = ResolverDataFetcherInstrumentation(
        dispatcherRegistry,
        dispatcherRegistry,
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

    private val viaductExecutionStrategyFactory =
        ViaductExecutionStrategy.Factory.Impl(
            dataFetcherExceptionHandler,
            ExecutionParameters.Factory(
                dispatcherRegistry,
                dispatcherRegistry,
                dispatcherRegistry,
                flagManager
            ),
            AccessCheckRunner(coroutineInterop),
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
        fragmentLoader,
        resolverDataFetcherInstrumentation,
        flagManager,
        this,
    )

    @Deprecated("Airbnb use only")
    override fun getGraphQL(): GraphQL {
        return graphql
    }

    override fun execute(executionInput: ExecutionInput): EngineExecutionResult {
        val gjExecutionInput = mkGJExecutionInput(executionInput)
        return EngineExecutionResult(
            deferredExecutionResult = graphql.executeAsync(gjExecutionInput).asDeferred()
        )
    }

    /**
     * This function is used to create the GraphQL-Java ExecutionInput that is needed to run the engine of GraphQL.
     *
     * @param executionInput The ExecutionInput object that has the data to create the input for execution
     *
     * @return GJExecutionInput created via the data inside the executionInput.
     */
    private fun mkGJExecutionInput(executionInput: ExecutionInput): GJExecutionInput {
        val executionInputBuilder =
            GJExecutionInput
                .newExecutionInput()
                .executionId(ExecutionId.generate())
                .query(executionInput.operationText)

        if (executionInput.operationName != null) {
            executionInputBuilder.operationName(executionInput.operationName)
        }
        executionInputBuilder.variables(executionInput.variables)
        val localContext = CompositeLocalContext.withContexts(mkEngineExecutionContext(executionInput.requestContext))

        @Suppress("DEPRECATION")
        return executionInputBuilder
            .context(executionInput.requestContext)
            .localContext(localContext)
            .graphQLContext(GraphQLJavaConfig.default.asMap())
            .build()
    }

    /**
     * Creates an instance of EngineExecutionContext. This should be called exactly once
     * per request and set in the graphql-java execution input's local context.
     */
    fun mkEngineExecutionContext(requestContext: Any?): EngineExecutionContext {
        return engineExecutionContextFactory.create(schema, requestContext)
    }
}
