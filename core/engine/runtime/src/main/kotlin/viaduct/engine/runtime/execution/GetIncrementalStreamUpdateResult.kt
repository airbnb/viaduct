package viaduct.engine.runtime.execution

import viaduct.engine.api.CompletedEntry
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult.Update
import viaduct.engine.api.PendingEntry

/**
 * Builds a response update from one batch of work events, as described in Section 6.4.7,
 * Mapping Work Events to Response Events.
 */
object GetIncrementalStreamUpdateResult {
    operator fun invoke(
        hasNext: Boolean,
        completed: List<CompletedEntry>,
        incremental: List<IncrementalEntry>,
        pending: List<PendingEntry>
    ): Update =
        UpdateExecutionResultImpl(
            hasNext = hasNext,
            incremental = incremental,
            completed = completed,
            pending = pending
        )
}
