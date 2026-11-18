package viaduct.engine.runtime2.model

import viaduct.graphql.schema.ViaductSchema

/**
 * A nominal type and unnormalized nested selections interpreted as one selection requirement.
 *
 * ### Invariant: fragment-selection-context
 *
 * [subselections] are the response-key-preserving flattened field occurrences of a
 * post-validation selection set interpreted with [nominalType], shared by construction and projection.
 *
 * ### Invariant: fragment-well-foundedness
 *
 * A fragment and its [subselections] form a finite, well-founded value. The selections may
 * be empty.
 *
 * Source field-resolver definitions use fragments while registry preparation preserves source
 * guards and occurrence shape. Canonical registry assembly specializes the root to its concrete
 * resolver owner and exposes the result as an [ObjectSelectionForest].
 */
sealed interface Fragment {
    /** The nominal type carried by this fragment. */
    val nominalType: ViaductSchema.CompositeTypeDef

    /** The response-key-preserving selections nested within this fragment. */
    val subselections: SelectionForest

    companion object {
        fun of(
            nominalType: ViaductSchema.CompositeTypeDef,
            subselections: SelectionForest
        ): Fragment = FragmentImpl(nominalType, subselections)
    }
}

private class FragmentImpl(
    override val nominalType: ViaductSchema.CompositeTypeDef,
    override val subselections: SelectionForest,
) : Fragment

/** Constructs an empty requirement that GraphQL fragment text cannot express. */
fun ViaductSchema.emptyFragmentOf(typeName: String): Fragment =
    Fragment.of(
        nominalType = requireType(typeName) as ViaductSchema.CompositeTypeDef,
        subselections = selectionForestOf(),
    )
