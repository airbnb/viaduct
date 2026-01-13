package viaduct.engine.runtime.execution

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.SubqueryExecutionException

/**
 * Extensions for [EngineExecutionContext.ExecutionHandle] to enable selection execution.
 *
 * These extensions are internal to the runtime module and allow accessing execution
 * context from the opaque ExecutionHandle interface.
 *
 * **Internal only**: End-users should never depend on these extensions or assume
 * the concrete type of [EngineExecutionContext.ExecutionHandle]. The handle is intentionally
 * opaque to allow future changes to the underlying implementation.
 */

/**
 * Extracts [ExecutionParameters] from an opaque [EngineExecutionContext.ExecutionHandle].
 *
 * This extension enables [viaduct.engine.api.Engine.executeSelectionSet] to access
 * the execution context needed for selection execution without exposing ExecutionParameters
 * in the public API.
 *
 * @throws SubqueryExecutionException if the handle is not an [ExecutionParameters] instance
 *         (e.g., a custom ExecutionHandle implementation was constructed).
 *
 * Note: Cross-engine or cross-request handle misuse is not validated here and is considered
 * undefined behavior. See subquery-execution.md for invariants.
 */
fun EngineExecutionContext.ExecutionHandle.asExecutionParameters(): ExecutionParameters {
    return this as? ExecutionParameters
        ?: throw SubqueryExecutionException.invalidExecutionHandle()
}
