package viaduct.engine.api

/**
 * Options for completing a selection set via [EngineExecutionContext.completeSelectionSet].
 *
 * This options class controls how already-resolved fields are completed into an ExecutionResult.
 * Unlike [ResolveSelectionSetOptions] which controls field resolution, these options affect the
 * completion phase where resolved values are transformed into the final result shape.
 *
 * ## Default Behavior
 *
 * With default options:
 * - Access checks are enforced during completion
 * - Standard ExecutionStepInfo handling is used
 *
 * ## Bypass Access Checks
 *
 * The [bypassAccessChecks] option allows completion to proceed without enforcing access checks.
 * This is useful when completing selections for checker execution, where the checker itself is
 * responsible for access control.
 *
 * ## Field Type Plan
 *
 * The [isFieldTypePlan] option affects how ExecutionStepInfo is constructed during completion.
 * When true, the plan is treated as a field-type plan which uses different parent step info
 * handling.
 *
 * ## Execution Handle Requirements
 *
 * These options require [EngineExecutionContext.executionHandle] to be non-null.
 * If the handle is null, completion will fail fast with [SubqueryExecutionException].
 *
 * @property bypassAccessChecks Whether to bypass access checks during completion.
 *           When true, access checks are skipped (e.g., when completing for checker execution).
 *           Default is false.
 * @property isFieldTypePlan Whether this is a field-type plan, which affects ExecutionStepInfo
 *           handling during completion. Default is false.
 */
data class CompleteSelectionSetOptions(
    val bypassAccessChecks: Boolean = false,
    val isFieldTypePlan: Boolean = false,
) {
    companion object {
        val DEFAULT = CompleteSelectionSetOptions()
    }
}
