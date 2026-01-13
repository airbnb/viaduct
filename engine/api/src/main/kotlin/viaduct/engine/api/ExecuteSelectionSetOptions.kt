package viaduct.engine.api

/**
 * Options for executing a selection set via [EngineExecutionContext.executeSelectionSet].
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
 * The [targetResult] option is only supported when:
 * - [EngineExecutionContext.executionHandle] is non-null, and
 * - the `ENABLE_SUBQUERY_EXECUTION_VIA_HANDLE` flag is enabled.
 *
 * If these conditions are not met and [targetResult] is set, execution will fail fast
 * with [SubqueryExecutionException].
 *
 * @property operationType Whether to execute against Query or Mutation root. Default is QUERY.
 * @property targetResult Optional [ObjectEngineResult] to populate with resolved field results.
 *           When null, a fresh result container is created for isolated execution.
 */
data class ExecuteSelectionSetOptions(
    val operationType: Engine.OperationType = Engine.OperationType.QUERY,
    val targetResult: ObjectEngineResult? = null,
) {
    companion object {
        val DEFAULT = ExecuteSelectionSetOptions()
        val MUTATION = ExecuteSelectionSetOptions(operationType = Engine.OperationType.MUTATION)
    }
}
