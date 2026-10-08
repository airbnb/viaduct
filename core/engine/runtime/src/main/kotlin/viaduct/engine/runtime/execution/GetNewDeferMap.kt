package viaduct.engine.runtime.execution

import graphql.execution.ResultPath

/**
 * Updates a [DeferMap] to reflect the provided `newDeferUsages` and `path`.
 * This may be called many times when processing lists of selection sets that
 * contain deferred fragments.
 *
 * Defined in section 6.5.1, mapping @defer directives to 'Delivery Groups'
 */
object GetNewDeferMap {
    operator fun invoke(
        newDeferUsages: List<DeferUsage>,
        path: ResultPath,
        deferMap: DeferMap
    ): DeferMap {
        if (newDeferUsages.isEmpty()) {
            return deferMap
        }

        val newDeferMap = deferMap.toMutableMap()
        for (deferUsage in newDeferUsages) {
            val (deferDirective, parentDeferUsage) = deferUsage
            val parent = newDeferMap[parentDeferUsage]
            newDeferMap[deferUsage] = DeferDeliveryGroup(path, deferDirective.label, parent)
        }
        return newDeferMap
    }
}
