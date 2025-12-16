package viaduct.engine.api

import graphql.ExecutionResult

/**
 * Core GraphQL execution engine that processes queries, mutations, and subscriptions
 * against a compiled Viaduct schema.
 */
interface Engine {
    val schema: ViaductSchema

    /**
     * Executes a GraphQL operation.
     *
     * @param executionInput The GraphQL operation to execute, including query text and variables
     * @return The completed GraphQL execution result containing data and errors
     */
    suspend fun execute(executionInput: ExecutionInput): ExecutionResult
}
