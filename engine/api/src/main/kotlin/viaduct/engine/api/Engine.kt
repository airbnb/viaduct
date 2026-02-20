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
     * This is an internal wiring-layer API. Prefer using [EngineExecutionContext.resolveSelectionSet]
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
     * @param selectionSet The [EngineSelectionSet] containing the fields to resolve.
     * @param options The [ResolveSelectionSetOptions] controlling execution behavior.
     * @return The resolved [EngineObjectData] wrapping the target result.
     * @throws SubqueryExecutionException on execution failures. See subquery-execution.md for details.
     */
    suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData

    /**
     * Completes a selection set against an ObjectEngineResult, transforming already-resolved
     * field values into an [ExecutionResult].
     *
     * This is an internal wiring-layer API. Prefer using [EngineExecutionContext.completeSelectionSet]
     * from the engine layer.
     *
     * Unlike [resolveSelectionSet] which triggers field resolution, this method waits for
     * already-in-progress resolution and transforms the resolved values into a completed result.
     * This is useful for shims executing classic DFPs on the modern engine, where field resolution
     * is triggered via RequiredSelectionSet and completion produces the final result.
     *
     * This method internally:
     * 1. Resolves RSS variables using the provided arguments and engine data from the handle
     * 2. Builds a QueryPlan from the selection set (cache-backed via QueryPlan.buildFromSelections)
     * 3. Waits for field resolution to complete
     * 4. Transforms the OER values into an ExecutionResult with data and errors
     *
     * The [executionHandle] must be obtained from [EngineExecutionContext.executionHandle]
     * within the same request. Do not cache, construct custom implementations, or share across requests.
     *
     * @param executionHandle The opaque handle from the current execution context.
     * @param selectionSet The [RequiredSelectionSet] containing the fields to complete.
     * @param targetResult The explicit OER to complete against; null uses parentEngineResult from handle.
     * @param arguments Field arguments for RSS variable resolution (e.g., from DataFetchingEnvironment.arguments).
     * @param options The [CompleteSelectionSetOptions] controlling completion behavior.
     * @return The completed [ExecutionResult] containing the data Map and any errors.
     * @throws SubqueryExecutionException if executionHandle is null or completion fails.
     */
    suspend fun completeSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: RequiredSelectionSet,
        targetResult: ObjectEngineResult?,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions,
    ): ExecutionResult

    /**
     * The type of operation for selection execution.
     */
    enum class OperationType {
        QUERY,
        MUTATION
    }
}
