package viaduct.engine.api

import graphql.ExecutionResult
import kotlinx.coroutines.Deferred

/**
 * Core GraphQL execution engine that processes queries, mutations, and subscriptions
 * against a compiled Viaduct schema.
 */
interface Engine {
    val schema: ViaductSchema

    /**
     * Executes a GraphQL operation asynchronously.
     *
     * @param executionInput The GraphQL operation to execute, including query text and variables
     * @return A deferred execution result that can be awaited
     */
    fun execute(executionInput: ExecutionInput): EngineExecutionResult
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
     * @return The completed GraphQL execution result containing data and errors.
     */
    suspend fun awaitExecutionResult(): ExecutionResult = deferredExecutionResult.await()
}
