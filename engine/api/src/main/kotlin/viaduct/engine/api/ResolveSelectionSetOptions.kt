package viaduct.engine.api

/**
 * Options for executing a selection set via [EngineExecutionContext.resolveSelectionSet].
 *
 * This options class provides flexibility for advanced use cases while keeping
 * the common tenant-level `ctx.query()` and `ctx.mutation()` APIs simple.
 *
 * ## Default Behavior
 *
 * With default options, execution behaves like [EngineExecutionContext.query]:
 * - Executes as a Query operation
 * - Creates a fresh [ObjectEngineResult] for isolated execution
 *
 * ## Memoization Control
 *
 * The [targetResult] parameter controls memoization:
 * - `null` (default): Creates a fresh [ObjectEngineResult] for isolated execution
 * - Existing [ObjectEngineResult]: Reuses memoized results from that container
 *
 * ## Execution Handle Requirements
 *
 * These options require [EngineExecutionContext.executionHandle] to be non-null.
 * If the handle is null, execution will fail fast with [SubqueryExecutionException].
 *
 * @property operationType Whether to execute against Query or Mutation root. Default is QUERY.
 * @property targetResult Optional [ObjectEngineResult] to populate with resolved field results.
 *           When null, a fresh result container is created for isolated execution.
 */
data class ResolveSelectionSetOptions(
    val operationType: Engine.OperationType = Engine.OperationType.QUERY,
    val targetResult: ObjectEngineResult? = null,
) {
    companion object {
        val DEFAULT = ResolveSelectionSetOptions()
        val MUTATION = ResolveSelectionSetOptions(operationType = Engine.OperationType.MUTATION)
    }
}
