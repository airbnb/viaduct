package viaduct.engine.api

import graphql.ExecutionResult
import viaduct.engine.runtime.ObjectEngineResult

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
     * Executes a selection set from within a resolver using an existing execution context,
     * returning a synchronous [EngineObjectData.Sync] with all fields eagerly resolved.
     *
     * @param executionHandle The opaque handle from the current execution context.
     * @param selectionSet The [EngineSelectionSet] containing the fields to resolve.
     * @param options The [ResolveSelectionSetOptions] controlling execution behavior.
     * @return The resolved [EngineObjectData.Sync] wrapping the target result.
     * @throws SubqueryExecutionException on execution failures.
     */
    suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync

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
     * 2. Builds a QueryPlan from the selection set (cache-backed via QueryPlanFactory.buildFromSelections)
     * 3. Waits for field resolution to complete
     * 4. Transforms the OER values into an ExecutionResult with data and errors
     *
     * The [executionHandle] must be obtained from [EngineExecutionContext.executionHandle]
     * within the same request. Do not cache, construct custom implementations, or share across requests.
     *
     * @param executionHandle The opaque handle from the current execution context.
     * @param selectionSet The [RequiredSelectionSet] containing the fields to complete.
     * @param targetResult The explicit OER to complete against; null uses the current object result from handle.
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
     * Resolves the object-valued field at [rootFieldPath] without traversing its nested selections.
     *
     * @param executionHandle The opaque handle from the current execution context.
     * @param rootFieldPath The path to the root object field, e.g. ["fooFactory", "create"]
     * @param arguments Field arguments
     * @param selectionSet The [EngineSelectionSet] to pass to the field resolver being executed, if it's
     *        selective. This will not actually resolve the nested selections.
     * @param options The [ResolveRootFieldReferenceOptions] controlling execution behavior.
     * @return The original [EngineObjectData] returned by the referenced field resolver, or null
     *         if the field resolves to null.
     */
    suspend fun resolveRootFieldReference(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        rootFieldPath: List<String>,
        arguments: Map<String, Any?>,
        selectionSet: EngineSelectionSet,
        options: ResolveRootFieldReferenceOptions,
    ): EngineObjectData?

    /**
     * The type of operation for selection execution.
     */
    enum class OperationType {
        QUERY,
        MUTATION
    }
}
