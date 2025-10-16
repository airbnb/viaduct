package viaduct.engine

import graphql.ExecutionInput as GJExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionId
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.preparsed.NoOpPreparsedDocumentProvider
import graphql.execution.preparsed.PreparsedDocumentProvider
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.future.asDeferred
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.TemporaryBypassAccessCheck
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.fragment.ViaductExecutableFragmentParser
import viaduct.engine.api.instrumentation.ChainedModernGJInstrumentation
import viaduct.engine.api.instrumentation.ViaductModernGJInstrumentation
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.execution.AccessCheckRunner
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.execution.ExecutionParameters
import viaduct.engine.runtime.execution.ViaductDataFetcherExceptionHandler
import viaduct.engine.runtime.execution.ViaductExecutionStrategy
import viaduct.engine.runtime.execution.WrappedCoroutineExecutionStrategy
import viaduct.engine.runtime.instrumentation.ResolverDataFetcherInstrumentation
import viaduct.engine.runtime.instrumentation.ScopeInstrumentation
import viaduct.engine.runtime.instrumentation.TaggedMetricInstrumentation
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.ResolverErrorBuilder
import viaduct.service.api.spi.ResolverErrorReporter

/**
 * Core GraphQL execution engine that processes queries, mutations, and subscriptions
 * against a compiled Viaduct schema.
 */
interface Engine {
    val schema: ViaductSchema

    /**
     * Factory for creating Engine instances with specific schema and document caching configurations.
     */
    interface Factory {
        /**
         * Creates a new Engine instance.
         *
         * @param schema The compiled Viaduct schema to validate against, but not used for execution except for introspection queries.
         * @param documentProvider Provider for preparsed and cached GraphQL documents.
         * @param fullSchema The full Viaduct schema, which is used for execution. Defaults to [schema] if not provided.
         * @return A configured Engine instance
         */
        fun create(
            schema: ViaductSchema,
            documentProvider: PreparsedDocumentProvider = NoOpPreparsedDocumentProvider(),
            fullSchema: ViaductSchema = schema,
        ): Engine
    }

    /**
     * Executes a GraphQL operation asynchronously.
     *
     * @param executionInput The GraphQL operation to execute, including query text and variables
     * @return A deferred execution result that can be awaited
     */
    fun execute(executionInput: ExecutionInput): EngineExecutionResult
}

@Deprecated("Airbnb use only")
interface EngineGraphQLJavaCompat {
    fun getGraphQL(): GraphQL
}

data class EngineConfiguration(
    val chainInstrumentationWithDefaults: Boolean = false,
) {
    companion object {
        val default = EngineConfiguration()
    }
}

class EngineImpl private constructor(
    override val schema: ViaductSchema,
    documentProvider: PreparsedDocumentProvider,
    fullSchema: ViaductSchema,
    private val config: EngineConfiguration,
    coroutineInterop: CoroutineInterop,
    dispatcherRegistry: DispatcherRegistry,
    fragmentLoader: FragmentLoader,
    flagManager: FlagManager,
    temporaryBypassAccessCheck: TemporaryBypassAccessCheck,
    dataFetcherExceptionHandler: DataFetcherExceptionHandler,
    private val meterRegistry: MeterRegistry?,
    private val additionalInstrumentation: Instrumentation?,
) : Engine, EngineGraphQLJavaCompat {
    @OptIn(ExperimentalCoroutinesApi::class)
    class FactoryImpl(
        private val config: EngineConfiguration = EngineConfiguration.default,
        private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
        private val dispatcherRegistry: DispatcherRegistry = DispatcherRegistry.Empty,
        private val fragmentLoader: FragmentLoader = ViaductFragmentLoader(ViaductExecutableFragmentParser()),
        private val flagManager: FlagManager = FlagManager.default,
        private val temporaryBypassAccessCheck: TemporaryBypassAccessCheck = TemporaryBypassAccessCheck.Default,
        private val dataFetcherExceptionHandler: DataFetcherExceptionHandler = ViaductDataFetcherExceptionHandler(
            ResolverErrorReporter.NoOpResolverErrorReporter,
            ResolverErrorBuilder.NoOpResolverErrorBuilder
        ),
        private val meterRegistry: MeterRegistry? = null,
        private val additionalInstrumentation: Instrumentation? = null,
    ) : Engine.Factory {
        override fun create(
            schema: ViaductSchema,
            documentProvider: PreparsedDocumentProvider,
            fullSchema: ViaductSchema,
        ): Engine {
            return EngineImpl(
                schema,
                documentProvider,
                fullSchema,
                config,
                coroutineInterop,
                dispatcherRegistry,
                fragmentLoader,
                flagManager,
                temporaryBypassAccessCheck,
                dataFetcherExceptionHandler,
                meterRegistry,
                additionalInstrumentation,
            )
        }
    }

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

/**
 * Wraps a deferred GraphQL execution result that can be awaited to retrieve the final output.
 */
data class EngineExecutionResult(
    private val deferredExecutionResult: Deferred<ExecutionResult>,
) {
    /**
     * Suspends until the GraphQL execution completes and returns the result.
     *
     * @return The completed GraphQL execution result containing data and errors
     */
    suspend fun awaitExecutionResult(): ExecutionResult = deferredExecutionResult.await()
}
