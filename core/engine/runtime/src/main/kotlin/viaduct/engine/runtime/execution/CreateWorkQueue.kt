package viaduct.engine.runtime.execution

import graphql.GraphQLError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the deferred tasks in [Work] and reports their results, as described in Section 6.4.6,
 * Yielding Incremental Stream Results.
 *
 * Events arrive in batches so completed fields, group completions, and the end of the queue
 * can be included in one response. Shared tasks run once, and nested groups become pending
 * after their parent succeeds.
 *
 * Execution starts when the queue is created. Events are buffered without waiting for a reader.
 * Each queue belongs to one request and can be collected only once.
 */
object CreateWorkQueue {
    class Result internal constructor(
        val initialGroups: List<DeferDeliveryGroup>,
        private val eventsChannel: ReceiveChannel<List<WorkEvent>>
    ) {
        /** a batch of [WorkEvent]s produced by the work queue */
        val events: Flow<List<WorkEvent>> = eventsChannel.consumeAsFlow()

        /** Cancels execution and discards buffered events, even before collection starts. */
        fun cancel() = eventsChannel.cancel()
    }

    private class WorkQueue(work: Work) {
        private val graph = WorkGraph(work)

        val initialGroups = graph.markInitialGroupsPending()

        suspend fun run(onEvents: suspend (List<WorkEvent>) -> Unit) =
            coroutineScope {
                // Internal coordination queue, which may be written to by multiple concurrent coroutines.
                val completions = Channel<Pair<ExecutionGroupTask, kotlin.Result<ExecutionGroupResult>>>(COMPLETION_CHANNEL_CAPACITY)
                val running = mutableMapOf<ExecutionGroupTask, Job>()

                try {
                    // Main work loop, in which we start tasks, process their results, and emit events until no groups remain pending.
                    // This loop will suspend while tasks are running.
                    while (true) {
                        // Claim any claimable tasks, run them in a new coroutine, then publish their result to the completions queue.
                        for (task in graph.claimTasks()) {
                            running[task] = launch {
                                val result = runCatching { task.run() }
                                // Cancelled tasks must still report completion so the graph can settle.
                                withContext(NonCancellable) {
                                    completions.send(task to result)
                                }
                            }
                        }

                        if (graph.hasPendingGroups()) {
                            // Check that there is a task capable of supplying the completion we're about to wait for
                            check(running.isNotEmpty()) { "Pending groups have no runnable tasks" }

                            // Accumulate a batch of all completed tasks.
                            // This suspends until at least one item is received.
                            val batch = completions.receiveBatch()

                            // Process each completed task result and update the graph to mark them as
                            // completed.
                            val results = batch.mapNotNull { (task, result) ->
                                running.remove(task)
                                if (graph.canDiscard(task)) return@mapNotNull null
                                task to result.getOrElse { error ->
                                    if (error !is GraphQLError) throw error
                                    ExecutionGroupResult(null, listOf(error), Work.empty)
                                }
                            }
                            graph.completeTasks(results)
                        }

                        // Advance the graph state and collect a batch of work events.
                        val events = graph.processPendingGroups()

                        // After the graph state has been advanced, identify any discardable tasks and
                        // cancel any in-flight work for those tasks.
                        val iterator = running.iterator()
                        while (iterator.hasNext()) {
                            val (task, job) = iterator.next()
                            if (graph.canDiscard(task)) {
                                job.cancel()
                                iterator.remove()
                            }
                        }

                        // Publish events batch
                        if (events.isNotEmpty()) onEvents(events)

                        // Finish producing events when no groups remain pending.
                        if (!graph.hasPendingGroups()) break
                    }
                } finally {
                    // Clean up when the queue finishes, fails, or is canceled.

                    // Cancel the coroutines of inflight tasks
                    running.values.forEach { it.cancel() }

                    // close the internal queue
                    completions.cancel()
                }
            }

        /** Waits for one result, then drains any other results that are available. */
        private suspend fun <T : Any> Channel<T>.receiveBatch(): List<T> =
            buildList {
                add(receive())
                while (true) {
                    add(tryReceive().getOrNull() ?: break)
                }
            }

        private companion object {
            // Allows large completion bursts before applying backpressure to task coroutines.
            const val COMPLETION_CHANNEL_CAPACITY = 1_024
        }
    }

    /**
     * Create a processing queue for the provided [work] and run
     * it in the provided [scope]
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("TooGenericExceptionCaught")
    operator fun invoke(
        work: Work,
        scope: CoroutineScope
    ): Result {
        val queue = WorkQueue(work)

        // Using an unlimited capacity effectively removes consumer backpressure.
        // This allows the queue to process and publish items as quickly as possible without
        // being affected by the consumer ability to process events.
        val events = scope.produce(capacity = Channel.UNLIMITED) {
            try {
                queue.run(::send)
            } catch (error: Throwable) {
                close(error)
            }
        }
        return Result(queue.initialGroups, events)
    }
}
