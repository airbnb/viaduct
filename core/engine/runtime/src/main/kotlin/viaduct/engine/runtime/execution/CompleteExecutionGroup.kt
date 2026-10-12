package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.NonNullableFieldWasNullException
import viaduct.engine.runtime.execution.CompletionErrors.FieldCompletionException

/**
 * Completes the fields for one task, returning data, errors, and any further deferred work.
 * This algorithm replaces `ExecuteExecutionGroup` from section 6.5.2, Collecting Execution Groups
 *
 * [CollectExecutionGroups] creates tasks for fields that will be added to the response later.
 * Each task calls [CompleteExecutionGroup] when it runs. For example:
 * ```graphql
 * {
 *   user {
 *     ... @defer(label: "summary") { name }
 *     ... @defer(label: "details") { name bio }
 *   }
 * }
 * ```
 *
 * [CollectExecutionGroups] creates 2 tasks at response path `["user"]`. When they run,
 * [CompleteExecutionGroup] returns a separate result for each task. For example:
 *   - `{"name": "Ada"}` as data for the `name` task, needed by both `summary` and `details`, and
 *   - `{"bio": "Enjoys hiking"}` as data for the `bio` task, needed only by `details`
 *
 * Each result contains only that task's errors. If one of its fields must have a value and fails,
 * the task's data is null. Further deferred parts discovered during completion are returned as [Work].
 */
object CompleteExecutionGroup {
    suspend operator fun invoke(
        ctx: ExecutionParameters,
        collectedFieldsMap: CollectedFieldsMap,
    ): ExecutionGroupResult {
        val groupCtx = ctx.copy(errorAccumulator = ErrorAccumulator())
        val completion = runCatching { groupCtx.constants.fieldCompleter.completeCollectedFields(groupCtx, collectedFieldsMap).await() }
        return buildResult(completion, groupCtx.errorAccumulator.toList())
    }

    internal fun buildResult(
        completion: Result<ExecutionPlanResult>,
        errors: List<GraphQLError>,
    ): ExecutionGroupResult =
        completion.fold(
            onSuccess = { completed -> ExecutionGroupResult(completed.data, errors, completed.work) },
            onFailure = { exception ->
                when (exception) {
                    is NonNullableFieldWasNullException, is FieldCompletionException -> {
                        if (errors.isEmpty()) throw exception
                        ExecutionGroupResult(null, errors, Work.empty)
                    }
                    else -> throw exception
                }
            },
        )
}
