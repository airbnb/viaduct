package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.MutationSelection
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection

/** Prepared namespace shape, shared by the compact, queued, and coroutine mutation traversals. */
internal class MutationNamespaceOccurrence(
    val occurrence: OEROccurrence,
    val selections: MutationSelectionForest,
) {
    val target = occurrence.target as MutationObjectEngineResult
    val members = selections.orderedSelections().map { selection ->
        selection to ObjectEngineResult.MutationKey.of(selection.key, selection.responseKey)
    }

    init {
        require(target.type == selections.type) { "Mutation forest does not match its namespace occurrence" }
        members.forEach { (_, key) -> target.reserveCell(key) }
    }

    fun child(
        selection: MutationSelection,
        key: ObjectEngineResult.MutationKey
    ): MutationNamespaceOccurrence {
        val forest = selection.subselections as MutationSelectionForest
        val result = MutationObjectEngineResult.of(forest)
        return MutationNamespaceOccurrence(OEROccurrence(occurrence.root, occurrence.coordinate(key), result, occurrence), forest)
    }
}

/** The ordinary resolver task uses the mutation publication key at its exact occurrence. */
internal fun MutationSelection.publicationSelection(key: ObjectEngineResult.MutationKey): ObjectSelection = ObjectSelection.of(key, possibleTypes, subselections)
