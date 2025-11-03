@file:OptIn(ExperimentalTime::class)

package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.execution.DataFetcherExceptionHandler
import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategy
import graphql.execution.ExecutionStrategyParameters
import graphql.execution.NonNullableFieldWasNullException
import graphql.language.OperationDefinition
import graphql.schema.GraphQLObjectType
import java.util.concurrent.CompletableFuture
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.execution.CompletionErrors.FieldCompletionException
import viaduct.engine.runtime.execution.CompletionErrors.NonNullableFieldWithErrorException
import viaduct.logging.ifDebug
import viaduct.utils.slf4j.logger

/**
 * A GraphQL execution strategy implementation for Viaduct that orchestrates the complete execution lifecycle
 * of GraphQL operations. This strategy follows GraphQL's execution specification while providing optimizations
 * for Viaduct's specific needs.
 *
 * The strategy coordinates three main phases of execution:
 * 1. Value Resolution - Fetches data through data fetchers and resolvers
 * 2. Value Completion - Transforms resolved data into the final GraphQL response format
 * 3. Error Handling - Manages errors according to GraphQL spec §6.4.4 for partial results
 *
 * Key features:
 * - Supports both serial and parallel execution modes
 * - Implements proper error bubbling for non-null fields
 * - Maintains GraphQL's partial result capabilities
 * - Coordinates the full execution lifecycle via [FieldResolver] and [FieldCompleter]
 * - Supports all GraphQL operation types (query, mutation, subscription)
 *
 * Error handling follows GraphQL specification rules:
 * - Non-null field errors bubble up and nullify parent fields
 * - Partial results are preserved when possible
 * - Field errors are collected and returned in the final result
 *
 * @property dataFetcherExceptionHandler Processes exceptions from data fetchers into GraphQL errors
 * @property executionParametersFactory factory for creating contextual data used during execution
 * @property isSerial When true, executes fields serially; when false, allows parallel execution
 *
 * @see FieldResolver Handles the resolution phase of execution
 * @see FieldCompleter Handles the completion phase of execution
 * @see [viaduct.engine.api.ObjectEngineResult] Stores intermediate execution results
 */
class ViaductExecutionStrategy internal constructor(
    private val dataFetcherExceptionHandler: DataFetcherExceptionHandler,
    private val executionParametersFactory: ExecutionParameters.Factory,
    private val accessCheckRunner: AccessCheckRunner,
    private val isSerial: Boolean,
    private val coroutineInterop: CoroutineInterop = DefaultCoroutineInterop,
    @Suppress("DEPRECATION")
    private val temporaryBypassAccessCheck: viaduct.engine.api.TemporaryBypassAccessCheck = viaduct.engine.api.TemporaryBypassAccessCheck.Default,
) : ExecutionStrategy(dataFetcherExceptionHandler) {
    /**
     * Factory interface for creating instances of [ViaductExecutionStrategy].
     */
    interface Factory {
        /**
         * Creates a configured instance of ViaductExecutionStrategy.
         *
         * @param isSerial When true, creates a strategy that executes fields serially.
         *                 When false, allows parallel field execution.
         * @return A new ViaductExecutionStrategy instance configured with the specified execution mode
         */
        fun create(isSerial: Boolean): ViaductExecutionStrategy

        /**
         * Default implementation of the [Factory] interface.
         */
        class Impl(
            private val dataFetcherExceptionHandler: DataFetcherExceptionHandler,
            private val executionParametersFactory: ExecutionParameters.Factory,
            private val accessCheckRunner: AccessCheckRunner,
            private val coroutineInterop: CoroutineInterop,
            @Suppress("DEPRECATION")
            private val temporaryBypassAccessCheck: viaduct.engine.api.TemporaryBypassAccessCheck,
        ) : Factory {
            override fun create(isSerial: Boolean): ViaductExecutionStrategy =
                ViaductExecutionStrategy(
                    dataFetcherExceptionHandler,
                    executionParametersFactory,
                    accessCheckRunner,
                    isSerial,
                    coroutineInterop,
                    temporaryBypassAccessCheck
                )
        }
    }

    companion object {
        private val log by logger()

        /**
         * Converts a [FieldCompletionResult] into an ExecutionResult that can be returned to graphql-java
         *
         * @param fieldCompletionResult The completion result to process
         * @param errors list of errors encountered during execution
         * @return Final ExecutionResult containing data and errors according to spec
         */
        @Suppress("UNCHECKED_CAST")
        fun buildExecutionResult(
            fieldCompletionResult: Result<FieldCompletionResult>,
            errors: List<GraphQLError>
        ): ExecutionResult {
            val data = fieldCompletionResult.mapCatching { fcr ->
                if (fcr.value is NonNullableFieldWasNullException) {
                    null
                } else {
                    fcr.value as? Map<String, Any?>
                }
            }.getOrNull()

            return ExecutionResult.newExecutionResult()
                .data(data)
                .errors(errors)
                .build()
        }

        /**
         * Processes execution errors according to GraphQL specification §6.4.4.
         *
         * Handles three categories of errors:
         * 1. NonNullableFieldWithErrorException - Field error in a non-null field
         * 2. NonNullableFieldWasNullException - Null value in a non-null field
         * 3. FieldCompletionException - General field execution errors
         *
         * Error handling rules:
         * - For non-null fields with errors, adds the error and bubbles null up
         * - For non-null fields that are null, bubbles up the null value
         * - For field completion errors, collects errors while preserving partial results
         * - For unhandled exceptions, treats as fatal and rethrows
         *
         * @param errors Mutable list to collect the processed errors
         * @param exception The exception to process
         * @throws Exception Various exceptions based on error handling rules
         */
        private fun addErrorsToExecutionResult(
            errors: MutableList<GraphQLError>,
            exception: Throwable
        ) {
            when (exception) {
                is NonNullableFieldWithErrorException -> {
                    // See GraphQL Spec 6.4.4 -- if field is non-null and has an error, we should add the underlying error and bubble the null
                    addErrorsToExecutionResult(errors, exception.underlyingException) // add the underlying exception to the errors array
                    throw exception.nonNullException // throw the non-null exception so that it bubbles
                }
                // See GraphQL Spec 6.4.4 -- if field is non-null and is null, we need to bubble up the non-null exception
                is NonNullableFieldWasNullException -> throw exception
                // capture any errors that occurred on the field itself
                is FieldCompletionException -> errors.addAll(exception.graphQLErrors)
                // unhandled, everything is broken, rethrow
                else -> throw exception
            }
        }
    }

    private val fieldCompleter = FieldCompleter(dataFetcherExceptionHandler, temporaryBypassAccessCheck)
    private val fieldResolver = FieldResolver(accessCheckRunner)

    /**
     * Executes a GraphQL operation according to the GraphQL specification §6.2.
     *
     * The execution process:
     * 1. Creates a root ObjectEngineResult based on the operation type
     * 2. Initializes execution parameters with the execution context
     * 3. Launches value resolution to fetch field data
     * 4. Completes the resolved values into final GraphQL response format
     * 5. Builds the execution result with any errors encountered
     *
     * @param executionContext Contains the parsed query, schema, and execution configuration
     * @param gjParameters GraphQL-Java specific execution parameters
     * @return CompletableFuture containing the final ExecutionResult
     */
    override fun execute(
        executionContext: ExecutionContext,
        gjParameters: ExecutionStrategyParameters
    ): CompletableFuture<ExecutionResult> =
        coroutineInterop.scopedFuture {
            val parameters = mkExecutionParameters(executionContext, gjParameters)
            val objType = parameters.executionStepInfo.type as GraphQLObjectType

            // return early if the query accesses the introspection schema when not allowed
            Introspection.checkIntrospection(parameters)?.let { err ->
                return@scopedFuture ExecutionResult.newExecutionResult().addError(err).build()
            }

            // 1. Fetch the selection set
            // 2. In parallel, complete the selection set,
            //    relying on the query plan and the object engine result
            parameters.launchOnRootScope {
                val (_, duration) = measureTimedValue {
                    if (isSerial) {
                        fieldResolver.fetchObjectSerially(objType, parameters)
                    } else {
                        fieldResolver.fetchObject(objType, parameters)
                    }
                }
                log.ifDebug {
                    debug("Took $duration to resolve query: ${executionContext.operationDefinition.operation.name}.")
                }
            }
            // Get list of completed FieldValueInfos
            val (queryResult, duration) = measureTimedValue {
                runCatching {
                    fieldCompleter.completeObject(parameters).asDeferred().await()
                }
            }
            log.ifDebug {
                debug("Took $duration to complete query: ${executionContext.operationDefinition.operation.name}.")
            }
            measureTimedValue {
                buildExecutionResult(queryResult, parameters.errorAccumulator.toList())
            }.let {
                log.ifDebug {
                    debug("Took ${it.duration} to build execution result: ${executionContext.operationDefinition.operation.name} ${it.value}.")
                }
                it.value
            }
        }

    /** Creates an ExecutionParameters configured with an ObjectEngineResult */
    private suspend fun mkExecutionParameters(
        executionContext: ExecutionContext,
        gjParameters: ExecutionStrategyParameters
    ): ExecutionParameters {
        val rootOER = createRootObjectEngineResult(executionContext)
        val queryOER = createQueryEngineResult(executionContext, rootOER)
        return executionParametersFactory.fromExecutionStrategyContextAndParameters(
            executionContext,
            gjParameters,
            rootOER,
            queryOER,
        )
    }

    /**
     * Creates the root ObjectEngineResult for the GraphQL operation.
     *
     * Initializes the appropriate root type based on operation:
     * - Query: Uses schema's query type
     * - Mutation: Uses schema's mutation type
     * - Subscription: Uses schema's subscription type
     *
     * @param executionContext Contains the operation definition and schema
     * @return ObjectEngineResult configured for the operation type
     * @throws IllegalStateException for unsupported operation types
     */
    private fun createRootObjectEngineResult(executionContext: ExecutionContext): ObjectEngineResultImpl {
        val operation = executionContext.operationDefinition.operation
        val schema = executionContext.graphQLSchema
        return when (operation) {
            OperationDefinition.Operation.QUERY -> ObjectEngineResultImpl.newForType(schema.queryType)
            OperationDefinition.Operation.MUTATION -> ObjectEngineResultImpl.newForType(schema.mutationType)
            OperationDefinition.Operation.SUBSCRIPTION -> ObjectEngineResultImpl.newForType(schema.subscriptionType)
            else -> throw IllegalStateException("Unsupported operation type: $operation")
        }
    }

    /**
     * Creates a query engine result for query selections.
     * Always returns an ObjectEngineResult of type Query, regardless of the operation type.
     * For Query operations, this will be the same instance as the root engine result.
     *
     * @param executionContext Contains the operation definition and schema
     * @param rootEngineResult The root engine result for this operation
     * @return ObjectEngineResult configured for Query type
     */
    private fun createQueryEngineResult(
        executionContext: ExecutionContext,
        rootEngineResult: ObjectEngineResultImpl
    ): ObjectEngineResultImpl {
        val operation = executionContext.operationDefinition.operation
        val schema = executionContext.graphQLSchema
        return when (operation) {
            OperationDefinition.Operation.QUERY -> rootEngineResult // Reuse the same instance
            OperationDefinition.Operation.MUTATION,
            OperationDefinition.Operation.SUBSCRIPTION -> ObjectEngineResultImpl.newForType(schema.queryType)
            else -> throw IllegalStateException("Unsupported operation type: $operation")
        }
    }
}
