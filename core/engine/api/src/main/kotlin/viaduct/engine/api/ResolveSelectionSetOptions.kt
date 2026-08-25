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
 * - Uses an isolated result context
 *
 * ## Execution Handle Requirements
 *
 * These options require [EngineExecutionContext.executionHandle] to be non-null.
 * If the handle is null, execution will fail fast with [SubqueryExecutionException].
 *
 * @property operationType Whether to execute against Query or Mutation root. Default is QUERY.
 * @property attribution Optional [ExecutionAttribution] for this subquery execution.
 */
data class ResolveSelectionSetOptions(
    val operationType: Engine.OperationType = Engine.OperationType.QUERY,
    val attribution: ExecutionAttribution = ExecutionAttribution.DEFAULT,
) {
    companion object {
        val DEFAULT = ResolveSelectionSetOptions()
        val MUTATION = ResolveSelectionSetOptions(operationType = Engine.OperationType.MUTATION)
    }
}
