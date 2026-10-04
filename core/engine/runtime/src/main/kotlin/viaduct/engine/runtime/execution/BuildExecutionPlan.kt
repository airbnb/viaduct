package viaduct.engine.runtime.execution

/**
 * Defined in 6.4.5 Execution Plan Generation
 *
 * @param collectedFieldsMap Fields belonging to the current execution group
 * @param newCollectedFieldsMaps Fields belonging to other execution groups, keyed by their normalized
 *   defer usage sets
 */
data class ExecutionPlan(
    val collectedFieldsMap: CollectedFieldsMap,
    val newCollectedFieldsMaps: Map<Set<DeferUsage>, CollectedFieldsMap>
)

/**
 * Section 6.4.5 Execution Plan Generation.
 *
 * [CollectFields] groups field occurrences by response name. [BuildExecutionPlan]
 * partitions those collected fields by the normalized defer usage sets computed
 * by [GetFilteredDeferUsageSet], preserving all field occurrences.
 *
 * Fields whose normalized usage set equals `parentDeferUsages` belong to the
 * current execution group, while every other distinct usage set defines a new group.
 *
 * Example:
 *   Given this query:
 *   ```
 *     {
 *       foo
 *       ... @defer(label: "A") { a, c }
 *       ... @defer(label: "B") { b, c }
 *     }
 *   ```
 *
 *   Then BuildExecutionPlan returns a result with the shape:
 *   ```
 *     {
 *       collectedFieldsMap: { foo -> [ foo ] }
 *       newCollectedFieldsMaps: {
 *         { "A" } -> { a -> [a @defer("A")] },
 *         { "B" } -> { b -> [b @defer("B")] },
 *         { "A", "B" } -> { c -> [c @defer("A"), c @defer("B")] }
 *       }
 *     }
 *   ```
 */
object BuildExecutionPlan {
    operator fun invoke(
        originalCollectedFieldsMap: CollectedFieldsMap,
        parentDeferUsages: Set<DeferUsage> = emptySet()
    ): ExecutionPlan {
        val collectedFieldsMap = mutableMapOf<String, CollectedField>()
        val newCollectedFieldsMaps = mutableMapOf<Set<DeferUsage>, MutableMap<String, CollectedField>>()

        for ((responseName, fieldsForResponseName) in originalCollectedFieldsMap) {
            val filteredDeferUsageSet = GetFilteredDeferUsageSet(fieldsForResponseName.occurrences)

            if (filteredDeferUsageSet == parentDeferUsages) {
                collectedFieldsMap[responseName] = fieldsForResponseName
            } else {
                val newCollectedFieldsMap =
                    newCollectedFieldsMaps.getOrPut(filteredDeferUsageSet) { mutableMapOf() }
                newCollectedFieldsMap[responseName] = fieldsForResponseName
            }
        }

        return ExecutionPlan(
            collectedFieldsMap,
            newCollectedFieldsMaps
        )
    }
}
