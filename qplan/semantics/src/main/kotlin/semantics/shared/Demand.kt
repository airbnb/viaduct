package semantics.shared

import model.ObjectEngineResult
import model.InclusionCondition
import model.ObjectSelectionForest
import model.SelectionForest
import model.guardedBy
import model.merge
import model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/** Construction demand distinguished by whether consuming each selected value enforces checks. */
internal class Demand<out S : SelectionForest>(
    val checked: S,
    val unchecked: S,
) {
    /** All values that must be constructed, independent of how their consumers read them. */
    val values: SelectionForest
        get() = checked + unchecked

    companion object {
        val EMPTY: Demand<SelectionForest> =
            Demand(selectionForestOf(), selectionForestOf())

        fun checked(selections: SelectionForest): Demand<SelectionForest> =
            Demand(selections, selectionForestOf())

        fun unchecked(selections: SelectionForest): Demand<SelectionForest> =
            Demand(selectionForestOf(), selections)
    }
}

/** Adds checked and unchecked demand independently. */
internal operator fun Demand<SelectionForest>.plus(
    other: Demand<SelectionForest>,
): Demand<SelectionForest> =
    Demand(
        checked = checked + other.checked,
        unchecked = unchecked + other.unchecked,
    )

/** Applies the same inclusion guard without losing demand provenance. */
internal fun Demand<SelectionForest>.guardedBy(
    condition: InclusionCondition,
): Demand<SelectionForest> =
    Demand(
        checked = checked.guardedBy(condition),
        unchecked = unchecked.guardedBy(condition),
    )

/** Normalizes each demand component independently for one concrete object type. */
internal fun Demand<SelectionForest>.merge(
    type: ViaductSchema.Object,
): Demand<ObjectSelectionForest> =
    Demand(
        checked = checked.merge(type),
        unchecked = unchecked.merge(type),
    )

/** Descendant provenance travels through value publication, including lists and references. */
internal fun Demand<ObjectSelectionForest>.descendants(
    key: ObjectEngineResult.ObjectKey,
): Demand<SelectionForest> =
    Demand(
        checked = checked.byKey()[key]?.subselections ?: selectionForestOf(),
        unchecked = unchecked.byKey()[key]?.subselections ?: selectionForestOf(),
    )
