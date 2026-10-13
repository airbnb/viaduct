package viaduct.engine.runtime.execution

/**
 * Tracks which tasks belong to each deferred group and when their results can be delivered.
 *
 * For example, in executing this query:
 * ```graphql
 * {
 *   ... @defer(label: "A") { x y }
 *   ... @defer(label: "B") { x }
 * }
 * ```
 *
 * Executing this query will create groups corresponding to labels A and B.
 * These groups have a many-to-many relationship with tasks -- in this query, one task will
 * be created for completing `x` in both groups, and another for completing `y` in group A.
 *
 * The relationships between groups and their tasks form a dependency graph. Calling
 * [markInitialGroupsPending] makes both A and B pending. If `x` finishes successfully first,
 * processing its result can deliver `x` once and let B succeed. A stays pending until `y`
 * also finishes successfully.
 *
 * The graph tracks group and task lifecycles through the operations below. The caller runs
 * tasks and handles the returned [WorkEvent] values.
 *
 * This class is not threadsafe. Calls must be serialized, even if tasks run concurrently.
 */
internal class WorkGraph(work: Work) {
    /** A task's progress through execution and delivery. */
    private sealed interface TaskStatus {
        /** Not yet claimed for execution. */
        data object Unclaimed : TaskStatus

        /** Claimed for execution, but no result has been recorded. */
        data object Claimed : TaskStatus

        /** Finished with a successful or failed result; no values have been delivered. */
        data class Completed(val result: ExecutionGroupResult) : TaskStatus

        /** Delivered once; the result is retained for other groups sharing the task. */
        data class Delivered(val result: ExecutionGroupResult) : TaskStatus
    }

    private class TaskState(val task: ExecutionGroupTask) {
        /** The execution and delivery status of [task]. */
        var status: TaskStatus = TaskStatus.Unclaimed

        /** The recorded result of [task], or null until it completes. */
        val result: ExecutionGroupResult?
            get() = when (val current = status) {
                TaskStatus.Unclaimed, TaskStatus.Claimed -> null
                is TaskStatus.Completed -> current.result
                is TaskStatus.Delivered -> current.result
            }
    }

    private enum class GroupStatus {
        /** Waiting to become pending, usually until its parent succeeds. */
        Waiting,

        /** Eligible for processing, but not yet succeeded or failed. */
        Pending,

        /** Finished successfully after being pending. */
        Succeeded,

        /** Failed while pending; its children cannot proceed. */
        Failed,

        /** Finished without becoming pending because no results remain to deliver. */
        Skipped,
    }

    private class GroupState(val group: DeferDeliveryGroup) {
        /**
         * Tasks that contribute results to this [group]. A task can be shared by several groups.
         *
         * For example:
         * ```graphql
         * {
         *   ... @defer(label: "A") { x y }
         *   ... @defer(label: "B") { x }
         * }
         * ```
         * A contains tasks for `x` and `y`; B contains the same task for `x`.
         */
        val tasks = mutableSetOf<TaskState>()

        /**
         * Groups whose [DeferDeliveryGroup.parent] is this [group], representing directly nested defers.
         *
         * For example:
         * ```graphql
         * {
         *   ... @defer(label: "A") {
         *     x
         *     ... @defer(label: "B") { y }
         *   }
         * }
         * ```
         * B is a child of A
         */
        val children = mutableSetOf<GroupState>()

        /** The processing status of [group] */
        var status = GroupStatus.Waiting
    }

    /** Registered delivery groups and their processing states. */
    private val groupStates = mutableMapOf<DeferDeliveryGroup, GroupState>()

    /**
     * Registered tasks and their execution and delivery states.
     * Groups sharing a task use the same [TaskState].
     */
    private val taskStates = mutableMapOf<ExecutionGroupTask, TaskState>()

    /**
     * Deferred groups with no parent, where [markInitialGroupsPending] starts.
     * Both A and B in the class example are roots.
     */
    private val roots = mutableSetOf<GroupState>()

    init {
        addAll(work)
    }

    /** Registers groups and tasks, including newly discovered tasks for existing groups. */
    private fun addAll(work: Work) {
        for (group in work.groups) {
            addGroup(group)
        }
        for (task in work.tasks) {
            if (task in taskStates) continue
            require(task.deferredFragments.isNotEmpty()) { "Deferred tasks must participate in a group" }
            val state = TaskState(task)
            taskStates[task] = state
            for (deferredFragment in task.deferredFragments) {
                val group = addGroup(deferredFragment)
                check(group.status == GroupStatus.Waiting || group.status == GroupStatus.Pending || isBlockedByFailure(group)) {
                    "Cannot add work to a succeeded or skipped group"
                }
                group.tasks.add(state)
            }
        }
    }

    private fun addGroup(group: DeferDeliveryGroup): GroupState {
        // if group was previously added, return its stored state
        groupStates[group]?.let { return it }

        // store the group state
        val state = GroupState(group)
        groupStates[group] = state

        val parent = group.parent
        if (parent == null) {
            // if group has no parent, add its state to the graph roots
            roots.add(state)
        } else {
            // otherwise, add this groups state to the parents states children
            addGroup(parent).children.add(state)
        }
        return state
    }

    /** Records claimed task completions and registers successful nested work */
    fun completeTasks(taskCompletions: List<Pair<ExecutionGroupTask, ExecutionGroupResult>>) {
        val newlyCompleted = buildList {
            for ((task, result) in taskCompletions) {
                val state = requireNotNull(taskStates[task]) { "Cannot complete an unregistered task" }
                require(state.status == TaskStatus.Claimed) { "Cannot complete a task that is not claimed" }
                state.status = TaskStatus.Completed(result)
                add(state)
            }
        }

        for (state in newlyCompleted) {
            val result = checkNotNull(state.result)
            if (result.data != null && !canDiscard(state.task)) {
                addAll(result.work)
            }
        }
    }

    /** Returns true if the group or any of its ancestors has failed. */
    private fun isBlockedByFailure(state: GroupState): Boolean {
        var current: GroupState? = state
        while (current != null) {
            if (current.status == GroupStatus.Failed) return true
            current = current.group.parent?.let { groupStates.getValue(it) }
        }
        return false
    }

    /**
     * Returns true when no remaining group can use the task's result.
     * Once a task can be discarded, it remains discardable.
     */
    fun canDiscard(task: ExecutionGroupTask): Boolean =
        task.deferredFragments.none {
            val groupState = groupStates.getValue(it)
            (groupState.status == GroupStatus.Waiting || groupState.status == GroupStatus.Pending) && !isBlockedByFailure(groupState)
        }

    /** Marks waiting groups as pending, skipping groups with no remaining results to deliver. */
    private fun markGroupsPending(candidates: Collection<GroupState>): List<DeferDeliveryGroup> =
        buildList {
            for (state in candidates) {
                if (isBlockedByFailure(state)) continue
                when (state.status) {
                    GroupStatus.Waiting -> {
                        if (state.tasks.any { it.status !is TaskStatus.Delivered }) {
                            state.status = GroupStatus.Pending
                            add(state.group)
                        } else {
                            state.status = GroupStatus.Skipped
                            addAll(markGroupsPending(state.children))
                        }
                    }
                    GroupStatus.Skipped -> addAll(markGroupsPending(state.children))
                    GroupStatus.Pending, GroupStatus.Succeeded, GroupStatus.Failed -> Unit
                }
            }
        }

    /** Marks initial groups as pending, skipping groups with no remaining results to deliver. */
    fun markInitialGroupsPending(): List<DeferDeliveryGroup> = markGroupsPending(roots)

    /** Claims each task once when any participating group is pending and not blocked by failure. */
    fun claimTasks(): List<ExecutionGroupTask> =
        buildList {
            for (group in groupStates.values) {
                if (group.status != GroupStatus.Pending || isBlockedByFailure(group)) continue
                for (state in group.tasks) {
                    if (state.status == TaskStatus.Unclaimed) {
                        state.status = TaskStatus.Claimed
                        add(state.task)
                    }
                }
            }
        }

    /** Processes pending groups, delivers successful task results, and reports group success or failure. */
    fun processPendingGroups(): List<WorkEvent> =
        buildList {
            do {
                var progressed = false

                // For any pending groups with failed tasks, advance the group to a failed state
                for (state in groupStates.values) {
                    if (state.status != GroupStatus.Pending || isBlockedByFailure(state)) continue
                    val failures = state.tasks.mapNotNull { it.result }.filter { it.data == null }
                    if (failures.isNotEmpty()) {
                        state.status = GroupStatus.Failed
                        add(WorkEvent.GroupFailure(state.group, failures.flatMap { it.errors }.distinct()))
                        progressed = true
                    }
                }

                for (state in groupStates.values) {
                    if (state.status != GroupStatus.Pending || isBlockedByFailure(state)) continue
                    if (state.tasks.all { it.result?.data != null }) {
                        for (task in state.tasks) {
                            if (task.status is TaskStatus.Delivered) continue
                            val result = checkNotNull(task.result)

                            // The GraphQL collection algorithms allow tasks and groups to have a many-to-many relationship.
                            // Despite this, the results of a task need to be delivered for a single "owning" delivery group,
                            // which the spec requires to be the one with the longest path among eligible groups.
                            val owner = task.task.deferredFragments
                                .map(groupStates::getValue)
                                .filter { group ->
                                    group.status == GroupStatus.Pending &&
                                        !isBlockedByFailure(group) &&
                                        group.tasks.none {
                                            it.result?.let { completed -> completed.data == null } == true
                                        }
                                }
                                .maxBy {
                                    // get group with the longest result path
                                    it.group.path.toList().size
                                }

                            // emit a GroupValues event for the owner group and mark the task as Delivered
                            add(
                                WorkEvent.GroupValues(
                                    owner.group,
                                    listOf(GroupValue(task.task.path, checkNotNull(result.data), result.errors))
                                )
                            )
                            task.status = TaskStatus.Delivered(result)
                        }

                        state.status = GroupStatus.Succeeded
                        add(WorkEvent.GroupSuccess(state.group, markGroupsPending(state.children)))
                        progressed = true
                    }
                }
            } while (progressed)

            if (!hasPendingGroups()) add(WorkEvent.Terminated)
        }

    /** Returns true while any announced group still needs to report success or failure. */
    fun hasPendingGroups(): Boolean =
        groupStates.values.any {
            it.status == GroupStatus.Pending && !isBlockedByFailure(it)
        }
}
