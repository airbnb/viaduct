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
