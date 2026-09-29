package semantics.shared

import model.InclusionCondition
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.SelectionForest
import model.guardedBy
import model.merge
import model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/**
 * Construction demand distinguished by whether consuming each selected value enforces checks.
 *
 * [checked] and [unchecked] classify fields inside the OER at the root of this demand.
 * [typeCheckDemanded] instead summarizes checked demand on that root OER itself. For a descended
 * OER, it is true exactly when at least one checked field occurrence demands the OER as an
 * object-valued base result; an ordinary checked resolution root is the corresponding entry-case
 * demand. A checked parent field therefore demands the reached OER's type check, while checked
 * fields added inside an OER during closure do not.
 *
 * This distinction means [typeCheckDemanded] cannot be derived from whether [checked] is empty.
 * Descending through a field establishes the bit from that field's checked provenance, combining
 * demands joins it with logical OR, and local demand closure preserves it.
 */
internal class Demand<out S : SelectionForest>(
    val checked: S,
    val unchecked: S,
    /** Whether the concrete OER at the root of this demand must produce a type-check result. */
    val typeCheckDemanded: Boolean,
) {
    /** All values that must be constructed, independent of how their consumers read them. */
    val values: SelectionForest
        get() = checked + unchecked

    companion object {
        /** No field or root-occurrence demand; the identity element for [plus]. */
        val EMPTY: Demand<SelectionForest> =
            Demand(
                checked = selectionForestOf(),
                unchecked = selectionForestOf(),
                typeCheckDemanded = false,
            )

        /** Ordinary checked demand requires the rooted occurrence's type-check result. */
        fun checked(
            selections: SelectionForest,
            typeCheckDemanded: Boolean = true,
        ): Demand<SelectionForest> =
            Demand(
                checked = selections,
                unchecked = selectionForestOf(),
                typeCheckDemanded = typeCheckDemanded,
            )

        /** Raw demand neither enforces field checks nor requires the rooted occurrence's type check. */
        fun unchecked(selections: SelectionForest): Demand<SelectionForest> =
            Demand(
                checked = selectionForestOf(),
                unchecked = selections,
                typeCheckDemanded = false,
            )
    }
}

/** Adds checked and unchecked demand independently. */
internal operator fun Demand<SelectionForest>.plus(other: Demand<SelectionForest>): Demand<SelectionForest> =
    Demand(
        checked = checked + other.checked,
        unchecked = unchecked + other.unchecked,
        typeCheckDemanded = typeCheckDemanded || other.typeCheckDemanded,
    )

/** Applies the same inclusion guard without losing demand provenance. */
internal fun Demand<SelectionForest>.guardedBy(condition: InclusionCondition): Demand<SelectionForest> =
    Demand(
        checked = checked.guardedBy(condition),
        unchecked = unchecked.guardedBy(condition),
        typeCheckDemanded = typeCheckDemanded,
    )

/** Normalizes each demand component independently for one concrete object type. */
internal fun Demand<SelectionForest>.merge(type: ViaductSchema.Object): Demand<ObjectSelectionForest> =
    Demand(
        checked = checked.merge(type),
        unchecked = unchecked.merge(type),
        typeCheckDemanded = typeCheckDemanded,
    )

/** Descendant provenance travels through value publication, including lists and references. */
internal fun Demand<ObjectSelectionForest>.descendants(key: ObjectEngineResult.ObjectKey): Demand<SelectionForest> =
    Demand(
        checked = checked.byKey()[key]?.subselections ?: selectionForestOf(),
        unchecked = unchecked.byKey()[key]?.subselections ?: selectionForestOf(),
        typeCheckDemanded = key in checked.byKey(),
    )
