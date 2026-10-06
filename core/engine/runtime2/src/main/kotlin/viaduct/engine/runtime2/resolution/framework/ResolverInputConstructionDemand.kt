package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.selectionForestOf

/** Direct object- and Query-fragment construction demand contributed by resolver occurrences. */
internal class ResolverInputConstructionDemand(
    val objectFragment: SelectionForest,
    val queryFragment: SelectionForest,
) {
    companion object {
        val EMPTY = ResolverInputConstructionDemand(selectionForestOf(), selectionForestOf())
    }
}
