package model

import viaduct.graphql.schema.ViaductSchema

/**
 * A nominal type and unnormalized nested selections interpreted as one selection requirement.
 *
 * ### Invariant: fragment-selection-context
 *
 * [materializeSelections] are the response-key-preserving flattened field occurrences of a
 * post-validation selection set interpreted with [nominalType]. [subselections] is their ordinary
 * construction view.
 *
 * ### Invariant: fragment-well-foundedness
 *
 * A fragment and its [materializeSelections] form a finite, well-founded value. The selections may
 * be empty.
 *
 * Test-fixture field-resolver definitions use fragments while fixture preparation preserves source
 * guards and occurrence shape. Canonical registry assembly specializes the root to its concrete
 * resolver owner and exposes the result as an [ObjectSelectionForest].
 */
sealed interface Fragment {
    /** The nominal type carried by this fragment. */
    val nominalType: ViaductSchema.CompositeTypeDef

    /** The response-key-preserving selections nested within this fragment. */
    val materializeSelections: MaterializeSelectionForest

    /** The ordinary construction view of [materializeSelections]. */
    val subselections: SelectionForest
        get() = materializeSelections.constructionSelections()

    companion object {
        fun of(
            nominalType: ViaductSchema.CompositeTypeDef,
            materializeSelections: MaterializeSelectionForest,
        ): Fragment = FragmentImpl(nominalType, materializeSelections)

        fun of(
            nominalType: ViaductSchema.CompositeTypeDef,
            subselections: SelectionForest,
        ): Fragment =
            FragmentImpl(
                nominalType,
                subselections.toCanonicalMaterializeSelectionForest(),
            )
    }
}

private class FragmentImpl(
    override val nominalType: ViaductSchema.CompositeTypeDef,
    override val materializeSelections: MaterializeSelectionForest,
) : Fragment
