package viaduct.engine.runtime.execution

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import viaduct.engine.api.CompletedEntry
import viaduct.engine.api.IncrementalEntry
import viaduct.engine.api.IncrementalExecutionResult.Update
import viaduct.engine.api.PendingEntry

/**
 * Turns each batch of [WorkEvent]s into one response update, as described in Section 6.4.7,
 * Mapping Work Events to Response Events.
 */
object MapIncrementalWorkEventsToResponseEvent {
    operator fun invoke(
        ctx: ExecutionParameters,
        workEventStream: Flow<List<WorkEvent>>
    ): Flow<Update> =
        workEventStream.map { events ->
            val pending = mutableListOf<PendingEntry>()
            val incremental = mutableListOf<IncrementalEntry>()
            val completed = mutableListOf<CompletedEntry>()
            var hasNext = true

            events.forEach { event ->
                when (event) {
                    is WorkEvent.GroupValues ->
                        event.values.forEach { value ->
                            incremental += GetIncrementalEntry(ctx, event.group, value)
                        }

                    is WorkEvent.GroupSuccess -> {
                        completed += GetCompletedEntry(ctx, event.group, emptyList())
                        pending += GetPendingEntries(ctx, event.newGroups)
                    }

                    is WorkEvent.GroupFailure -> {
                        completed += GetCompletedEntry(ctx, event.group, event.errors)
                    }

                    is WorkEvent.Terminated -> {
                        hasNext = false
                    }
                }
            }

            GetIncrementalStreamUpdateResult(
                hasNext,
                completed,
                incremental,
                pending
            )
        }
}
