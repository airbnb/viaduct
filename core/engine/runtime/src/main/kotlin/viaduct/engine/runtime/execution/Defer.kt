package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.language.Directive

/** An active defer identified by its directive and resolved label. */
data class Defer(val label: String?, private val directive: Directive)

/** A defer occurrence and its enclosing defer context. */
data class DeferUsage(val defer: Defer, val parent: DeferUsage?)

/**
 * DeferDeliveryGroup is an execution-time representation of a `@defer`, representing
 * the fulfillment of a `@defer` for a specific position in a response tree.
 *
 * It is defined by the spec in 6.5.1 GetNewDeferMap as the informal term
 * "deferred fragment".
 *
 * For example, consider this query:
 * ```graphql
 *  {
 *    list {
 *      ... @defer(label: "A") { a }
 *    }
 *  }
 * ```
 *
 * A value of `DeferDeliveryGroup` for this query might be
 * `DeferDeliveryGroup(["list", 1], "A", null)`, which describes the deferred result for
 * the second object returned by `list`.
 *
 * This can be contrasted to a [DeferUsage] value like `DeferUsage("A", null)`, which
 * describes the unexecuted textual occurrence of a `@defer`.
 */
class DeferDeliveryGroup(val path: ResultPath, val label: String?, val parent: DeferDeliveryGroup?)

/**
 * A binding of query-level [DeferUsage] to its [DeferDeliveryGroup] execution representation.
 *
 * Defined in 6.5.1 Mapping @defer Directives to Delivery Groups
 */
typealias DeferMap = Map<DeferUsage, DeferDeliveryGroup>

/**
 * [ExecutionGroupTask] represents the future completion of an execution group at a specific
 * position in the response tree, as described in 6.5.2 Collecting Execution Groups.
 *
 * A task can contribute to multiple [DeferDeliveryGroup] instances. For example:
 * ```graphql
 *  {
 *    node {
 *      ... @defer(label: "A") { x }
 *      ... @defer(label: "B") { x }
 *    }
 *  }
 * ```
 *
 * [CollectFields] combines both selections of `x` under the same response name.
 * [BuildExecutionPlan] places this field in one execution group with defer usages `{A, B}`.
 * Its task completes `x` at `["node"]` and contributes to both delivery groups.
 *
 * A delivery group's fields can span several tasks, and a task can complete fields shared
 * by several groups.
 *
 * @param deferredFragments Delivery groups that this task contributes to.
 * @property path Absolute response path of the object whose fields are completed
 * @param execute Completion function invoked by [run].
 */
class ExecutionGroupTask(
    val deferredFragments: Set<DeferDeliveryGroup>,
    val path: ResultPath,
    private val execute: suspend () -> ExecutionGroupResult
) {
    /** Runs the completion function, returning data, errors, and any nested [Work]. */
    suspend fun run(): ExecutionGroupResult = execute()
}

/**
 * The data, errors, and deferred work produced by completing an execution group.
 *
 * @property data Completed fields by response name, or null if the group fails.
 * @property errors Errors from this group. Must be nonempty when [data] is null.
 * @property work Deferred delivery groups and tasks discovered during completion.
 */
data class ExecutionGroupResult(
    val data: Map<String, Any?>?,
    val errors: List<GraphQLError>,
    val work: Work
) {
    init {
        require(data != null || errors.isNotEmpty()) { "Failed execution groups must contain errors" }
    }
}

/**
 * The data and deferred work returned by an execution plan, as described in
 * 6.5 Executing an Execution Plan.
 *
 * @property data Completed fields by response name.
 * @property work Deferred delivery groups and tasks returned by the plan and its field completions.
 */
data class ExecutionPlanResult(
    val data: Map<String, Any?>,
    val work: Work
) {
    companion object {
        fun fromFieldResults(fields: Map<String, FieldCompletionResult>): ExecutionPlanResult =
            ExecutionPlanResult(
                fields.mapValues { (_, result) -> result.value },
                fields.values.fold(Work.empty) { work, result -> work + result.work },
            )
    }
}

/**
 * [Work] represents deferred delivery groups and tasks discovered during completion,
 * as described in 6.3.3 Executing Collected Fields.
 *
 * For example, in this query:
 * ```graphql
 *  {
 *    list {
 *      ... @defer(label: "A") { a }
 *    }
 *  }
 * ```
 *
 * Completing two list items can contribute separate delivery groups at `["list", 0]` and
 * `["list", 1]`, each with a task completing `a`.
 *
 * @property groups Delivery groups to register for deferred delivery.
 * @property tasks Tasks to run, each of which can contribute to multiple delivery groups.
 */
data class Work(
    val groups: Set<DeferDeliveryGroup>,
    val tasks: List<ExecutionGroupTask>,
) {
    operator fun plus(other: Work): Work =
        if (other == empty) {
            this
        } else {
            copy(
                groups = groups + other.groups,
                tasks = tasks + other.tasks,
            )
        }

    companion object {
        /** Represents completion that produces no deferred work. */
        val empty: Work = Work(emptySet(), emptyList())
    }
}

/**
 * Additional fields completed at a position in the response
 *
 * @property path Absolute response path where [data] belongs, including aliases and list indices.
 * @property data Additional fields by response name, rather than a replacement for the object.
 * @property errors Field errors accompanying [data]. Failures that prevent producing data are
 *   reported through [WorkEvent.GroupFailure] instead.
 */
data class GroupValue(
    val path: ResultPath,
    val data: Map<String, Any?>,
    val errors: List<GraphQLError>
)

/**
 * An update from [WorkGraph], before it is turned into a client response, as described in
 * Section 6.4.7, Mapping Work Events to Response Events.
 *
 * Events arrive in batches, so delivering values, completing groups, and ending the stream can
 * all happen in the same response.
 */
sealed interface WorkEvent {
    /**
     * Delivers completed [values] for [group]. Shared task results are delivered once under one
     * participating group. Other tasks may still need to finish before the group completes.
     *
     * @property group An announced participating group with the longest response path, as required
     *   by the Incremental Object Result section of the specification.
     * @property values Results to deliver. Their paths may extend below [group]'s path.
     */
    data class GroupValues(val group: DeferDeliveryGroup, val values: List<GroupValue>) : WorkEvent

    /**
     * All work belonging to [group] has completed successfully.
     *
     * @property newGroups Groups now eligible for delivery, including descendants of empty groups.
     */
    data class GroupSuccess(val group: DeferDeliveryGroup, val newGroups: List<DeferDeliveryGroup>) : WorkEvent

    /**
     * Completes [group] with [errors] from failed execution. Errors accompanying successfully
     * produced data belong in [GroupValues] instead.
     */
    data class GroupFailure(val group: DeferDeliveryGroup, val errors: List<GraphQLError>) : WorkEvent

    /** The entire work queue has finished. This can accompany group events in the same batch. */
    object Terminated : WorkEvent
}
