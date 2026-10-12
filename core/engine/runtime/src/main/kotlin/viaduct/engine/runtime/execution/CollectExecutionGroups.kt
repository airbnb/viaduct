package viaduct.engine.runtime.execution

/**
 * Prepares tasks for fields that will be added to the response later.
 * This algorithm is defined by section 6.5.2, Collecting Execution Groups
 *
 * A task describes which fields to complete at a specific response path and which `@defer`
 * directives need them. For example:
 * ```graphql
 * {
 *   user {
 *     ... @defer(label: "summary") { name }
 *     ... @defer(label: "details") { name bio }
 *   }
 * }
 * ```
 *
 * [BuildExecutionPlan] separates these fields into groups, and [CollectExecutionGroups] turns
 * each group into a task. For this query, it returns 2 tasks at response path `["user"]`:
 *   - a task for `name`, needed by both `summary` and `details`, and
 *   - a task for `bio`, needed only by `details`
 */
object CollectExecutionGroups {
    operator fun invoke(
        ctx: ExecutionParameters,
        newCollectedFieldsMaps: Map<Set<DeferUsage>, CollectedFieldsMap>,
    ): List<ExecutionGroupTask> =
        newCollectedFieldsMaps.map { (deferUsageSet, collectedFieldsMap) ->
            val deferredFragments = deferUsageSet.map { deferUsage ->
                checkNotNull(ctx.deferMap[deferUsage]) { "Missing delivery group for defer usage $deferUsage" }
            }.toSet()

            // initialize a task that will complete this execution group when run
            ExecutionGroupTask(deferredFragments, ctx.path) {
                CompleteExecutionGroup(ctx.copy(deferUsageSet = deferUsageSet), collectedFieldsMap)
            }
        }
}
