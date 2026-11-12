@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.resolution.framework.CycleCheckState
import viaduct.engine.runtime2.resolution.framework.ResolverReadCycleException
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

/**
 * Resolves selective demand once per object-local symbolic key on [coroutineContext].
 *
 * Keys coalesce when their fields and argument expressions are equal. Variables in those
 * expressions identify their owning resolver occurrences, so equal uses of one variable instance
 * coalesce while variables owned by different resolver occurrences remain distinct. The caller
 * owns the context and its execution resources; the resolver neither retains nor closes them.
 */
fun SharedOperationContext<*>.resolve(
    selections: SelectionForest,
    coroutineContext: CoroutineContext,
): ObjectEngineResult =
    runBlocking(coroutineContext) {
        resolveWithTimeout(selections, 15_000)
    }

/** Bounded Resolution collects dependencies and diagnoses cycles only at its own deadline. */
suspend fun SharedOperationContext<*>.resolveWithTimeout(
    selections: SelectionForest,
    timeoutMillis: Long,
): ObjectEngineResult {
    val callerContext = currentCoroutineContext()
    val cycleChecker = CycleCheckState.createDeferred()
    return withTimeout(timeoutMillis) {
        try {
            coroutineScope {
                startResolution(
                    selections = selections,
                    requestScope = this,
                    cycleChecker = cycleChecker,
                ).root
            }
        } catch (timeout: TimeoutCancellationException) {
            // Inside this timeout's body its job is cancelled only by its own deadline or
            // caller cancellation, not by a nested timeout escaping coroutineScope above.
            if (!isActive && callerContext.isActive) {
                cycleChecker.findCycle()?.let { cycle ->
                    throw ResolverReadCycleException(cycle).also { it.initCause(timeout) }
                }
            }
            throw timeout
        }
    }
}
