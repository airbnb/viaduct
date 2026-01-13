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

    /**
     * Executes a selection set from within a resolver using an existing execution context.
     *
     * This is an internal wiring-layer API. Prefer using [EngineExecutionContext.executeSelectionSet]
     * from the engine layer, or the tenant-level `ctx.query()`/`ctx.mutation()` methods.
     *
     * This method enables resolvers to execute additional queries or mutations against the
     * schema without rebuilding GraphQL-Java state. It uses the [ExecutionHandle][EngineExecutionContext.ExecutionHandle]
     * to access the current execution context and resolves the provided selections into
     * the target result specified in [options].
     *
     * The [executionHandle] must be obtained from [EngineExecutionContext.executionHandle]
     * within the same request. Do not cache, construct custom implementations, or share across requests.
     *
     * @param executionHandle The opaque handle from the current execution context.
     * @param selectionSet The [RawSelectionSet] containing the fields to resolve.
     * @param options The [ExecuteSelectionSetOptions] controlling execution behavior.
     * @return The resolved [EngineObjectData] wrapping the target result.
     * @throws SubqueryExecutionException on execution failures. See subquery-execution.md for details.
     */
    suspend fun executeSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: RawSelectionSet,
        options: ExecuteSelectionSetOptions,
    ): EngineObjectData

    /**
     * The type of operation for selection execution.
     */
    enum class OperationType {
        QUERY,
        MUTATION
    }
}
