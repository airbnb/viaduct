package viaduct.engine

import graphql.ExecutionInput as GJExecutionInput
import graphql.ExecutionResult
import graphql.GraphQL
import graphql.execution.ExecutionId
import graphql.execution.ExecutionStrategy
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.preparsed.PreparsedDocumentProvider
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.future.asDeferred
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.EngineExecutionContextFactory

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
         * @param schema The compiled Viaduct schema to execute against
         * @param documentProvider Provider for preparsed and cached GraphQL documents
         * @return A configured Engine instance
         */
        fun create(
            schema: ViaductSchema,
            documentProvider: PreparsedDocumentProvider
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

class EngineImpl private constructor(
    override val schema: ViaductSchema,
    private val queryExecutionStrategy: ExecutionStrategy,
    private val mutationExecutionStrategy: ExecutionStrategy,
    private val subscriptionExecutionStrategy: ExecutionStrategy,
    private val instrumentation: Instrumentation,
    private val engineExecutionContextFactory: EngineExecutionContextFactory,
    documentProvider: PreparsedDocumentProvider,
) : Engine, EngineGraphQLJavaCompat {
    class FactoryImpl
        @Inject
        constructor(
            @Named("QueryExecutionStrategy") private val queryExecutionStrategy: ExecutionStrategy,
            @Named("MutationExecutionStrategy") private val mutationExecutionStrategy: ExecutionStrategy,
            @Named("SubscriptionExecutionStrategy") private val subscriptionExecutionStrategy: ExecutionStrategy,
            private val instrumentation: Instrumentation,
            private val engineExecutionContextFactory: EngineExecutionContextFactory,
        ) : Engine.Factory {
            override fun create(
                schema: ViaductSchema,
                documentProvider: PreparsedDocumentProvider
            ): Engine {
                return EngineImpl(
                    schema,
                    queryExecutionStrategy,
                    mutationExecutionStrategy,
                    subscriptionExecutionStrategy,
                    instrumentation,
                    engineExecutionContextFactory,
                    documentProvider,
                )
            }
        }

    private val graphql = GraphQL.newGraphQL(schema.schema)
        .preparsedDocumentProvider(IntrospectionRestrictingPreparsedDocumentProvider(documentProvider))
        .queryExecutionStrategy(queryExecutionStrategy)
        .mutationExecutionStrategy(mutationExecutionStrategy)
        .subscriptionExecutionStrategy(subscriptionExecutionStrategy)
        .instrumentation(instrumentation)
        .build()

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
