package viaduct.engine.api

/**
 * Container for the pair of required selection sets (object and query) produced by a factory.
 *
 * This is a lightweight wrapper around two nullable [RequiredSelectionSet] instances,
 * representing the object-level and query-level selections that a resolver requires.
 */
data class RequiredSelectionSets(
    val objectSelections: RequiredSelectionSet?,
    val querySelections: RequiredSelectionSet?,
) {
    companion object {
        private val EMPTY = RequiredSelectionSets(objectSelections = null, querySelections = null)

        fun empty(): RequiredSelectionSets = EMPTY
    }
}
