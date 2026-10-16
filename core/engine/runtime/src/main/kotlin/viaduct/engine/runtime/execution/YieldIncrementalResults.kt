package viaduct.engine.runtime.execution

import graphql.ExecutionResult
import graphql.GraphQLError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext

/**
 * Returns the initial response and starts its deferred work, as described in Section 6.4.6,
 * Yielding Incremental Stream Results. Without deferred tasks, returns a complete response.
 */
object YieldIncrementalResults {
    suspend operator fun invoke(
        parameters: ExecutionParameters,
        data: Map<String, Any?>,
        errors: List<GraphQLError>,
        work: Work,
    ): ExecutionResult {
        val initialResult = ExecutionResult.newExecutionResult().data(data).errors(errors).build()
        if (work.tasks.isEmpty()) return initialResult

        val queue = CreateWorkQueue(work, CoroutineScope(currentCoroutineContext()))
        return IncrementalExecutionResultImpl(
            initialResult = initialResult,
            pending = GetPendingEntries(parameters, queue.initialGroups),
            hasNext = true,
            updates = MapIncrementalWorkEventsToResponseEvent(parameters, queue.events),
            cancelExecution = queue::cancel,
        )
    }
}
