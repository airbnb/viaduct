package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.planMemo
import viaduct.engine.runtime2.model.updatePlanMemo

/**
 * One pure demand analysis memoized on the forest instance it consumes. Selection equality is
 * undefined, so the forest's identity is the primary key; each entry records and compares every
 * remaining input its computation reads. Resolution owns the concrete entry kinds.
 */
internal interface ForestPlanEntry

/** Bounds each entry kind per forest so pathological key diversity cannot grow a long-lived forest. */
private const val MAX_FOREST_PLAN_ENTRIES_PER_KIND = 16

/** The entries attached to one forest; replaced copy-on-write under the forest's monitor. */
private class ForestPlanMemo(
    val entries: List<ForestPlanEntry>,
)

/** Returns the first attached entry of kind [T] that satisfies [matches]. */
internal inline fun <reified T : ForestPlanEntry> SelectionForest.findForestPlanEntry(matches: (T) -> Boolean): T? {
    forestPlanEntries().forEach { entry -> if (entry is T && matches(entry)) return entry }
    return null
}

@PublishedApi
internal fun SelectionForest.forestPlanEntries(): List<ForestPlanEntry> = (planMemo() as? ForestPlanMemo)?.entries.orEmpty()

/** Attaches [entry] unless this forest cannot carry a memo or already holds the bounded number of its kind. */
internal fun SelectionForest.addForestPlanEntry(entry: ForestPlanEntry) {
    updatePlanMemo { current ->
        val entries = (current as? ForestPlanMemo)?.entries.orEmpty()
        if (entries.count { it.javaClass == entry.javaClass } >= MAX_FOREST_PLAN_ENTRIES_PER_KIND) {
            current
        } else {
            ForestPlanMemo(entries + entry)
        }
    }
}

/** The forest that carries a demand's memo and its partner component, or null for empty demand. */
internal fun Demand<SelectionForest>.forestPlanMemoOwner(): Pair<SelectionForest, SelectionForest>? =
    when {
        !checked.isEmpty() -> checked to unchecked
        !unchecked.isEmpty() -> unchecked to checked
        else -> null
    }

private class PassiveDemandEntry(
    val invocationDemand: SelectionForest,
    val passiveDemand: SelectionForest,
) : ForestPlanEntry

/**
 * Returns `this + closedValues` as one stable forest per pair of instances, so the per-forest merge
 * memo also serves repeated passive descent through the same closed object.
 */
internal fun SelectionForest.passiveDemandWithPlanCache(closedValues: SelectionForest): SelectionForest {
    if (isEmpty() || closedValues.isEmpty()) return this + closedValues
    closedValues.findForestPlanEntry<PassiveDemandEntry> { it.invocationDemand === this }?.let { return it.passiveDemand }
    val passiveDemand = this + closedValues
    closedValues.addForestPlanEntry(PassiveDemandEntry(this, passiveDemand))
    return passiveDemand
}
