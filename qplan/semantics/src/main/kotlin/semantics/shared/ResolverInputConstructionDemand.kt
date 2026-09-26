package semantics.shared

import model.SelectionForest
import model.selectionForestOf

/** Direct object- and Query-fragment construction demand contributed by resolver occurrences. */
internal class ResolverInputConstructionDemand(
    val objectFragment: SelectionForest,
    val queryFragment: SelectionForest,
) {
    companion object {
        val EMPTY = ResolverInputConstructionDemand(selectionForestOf(), selectionForestOf())
    }
}
