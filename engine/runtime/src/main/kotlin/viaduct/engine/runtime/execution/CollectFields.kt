package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.execution.MergedField
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLSchema
import viaduct.engine.runtime.execution.Constraints.Resolution
import viaduct.engine.runtime.execution.QueryPlan.CollectedField
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.FragmentDefinition
import viaduct.engine.runtime.execution.QueryPlan.FragmentSpread
import viaduct.engine.runtime.execution.QueryPlan.Fragments
import viaduct.engine.runtime.execution.QueryPlan.InlineFragment
import viaduct.engine.runtime.execution.QueryPlan.Selection
import viaduct.engine.runtime.execution.QueryPlan.SelectionSet
import viaduct.utils.collections.MaskedSet

object CollectFields {
    /**
     * Apply the CollectFields algorithm to the given selection set
     * This method is "shallow", in that while it will traverse inline fragments and fragment spreads,
     * it will not traverse field subselections.
     * This method is "strict", in that if any selection cannot be collected, it will throw
     * an exception.
     */
    fun shallowStrictCollect(
        schema: GraphQLSchema,
        selectionSet: SelectionSet,
        variables: CoercedVariables,
        parentType: GraphQLObjectType,
        fragments: Fragments
    ): SelectionSet {
        val result = collect(
            State(
                schema = schema,
                acc = emptyList(),
                pending = selectionSet.selections,
                spreadFragments = emptySet(),
                fragments = fragments,
                constraintsCtx = Constraints.Ctx(variables, MaskedSet(listOf(parentType)))
            )
        )
        return result.asSelectionSet()
    }

    /** models the state while collecting fields within a single SelectionSet */
    private data class State(
        val schema: GraphQLSchema,
        val acc: List<Selection>,
        val pending: List<Selection>,
        val spreadFragments: Set<String>,
        val fragments: Fragments,
        val constraintsCtx: Constraints.Ctx,
    ) {
        fun fragmentDef(name: String): FragmentDefinition = requireNotNull(fragments[name]) { "Fragment `$name` is not defined" }

        fun asSelectionSet(): SelectionSet = SelectionSet(acc)

        fun constrainedTypes() = constraintsCtx.parentTypes.toSet()
    }

    /**
     * Collect pending selections in State, according to the spec's definition for
     * CollectFields
     *  see https://spec.graphql.org/draft/#CollectFields()
     *
     * @see Constraints
     */
    private fun collect(state: State): State {
        val visitedFragments = state.spreadFragments.toMutableSet()
        val acc = ArrayList<Selection>(state.pending.size)

        // map of resultKey to index of collected field in acc
        val collectedFieldIndices = mutableMapOf<String, Int>()

        // the inner loop will both push and pop from the front of the queue
        // For example, we might handle an inline fragment by popping off the inline fragment
        // selection, and then pushing on the field selections of that fragment.
        // An ArrayDeque is a good data structure for this job, as it has constant-time reads/writes
        // when working at the front, and is more memory-efficient than a LinkedList
        val queue = ArrayDeque(state.pending)

        while (queue.isNotEmpty()) {
            val sel = queue.removeFirst()
            val resolution = sel.constraints.solve(state.constraintsCtx)

            when {
                resolution == Resolution.Drop -> continue

                resolution == Resolution.Unsolved ->
                    // We've encountered an Unsolved Constraints, indicating that we
                    // cannot completely collect this selection set.
                    throw IllegalStateException("Could not collect selection: $sel")

                // getting to this point implies that resolution == Resolution.Collect
                sel is CollectedField ->
                    when (val extantIndex = collectedFieldIndices[sel.responseKey]) {
                        null -> {
                            // no existing field with this responseKey
                            // Nothing to merge with, and we can always add this selection to the accumulator
                            collectedFieldIndices[sel.responseKey] = acc.size
                            acc += sel
                        }

                        else -> {
                            // we've already collected a field with this responseKey.
                            // Look it up by its index and merge
                            val extant = acc[extantIndex] as CollectedField
                            acc[extantIndex] = merge(extant, sel)
                        }
                    }

                sel is Field ->
                    // Collect this field by pushing a CollectedField onto the stack.
                    // It will be merged in the next iteration.
                    queue.addFirst(
                        CollectedField(
                            sel.resultKey,
                            sel.selectionSet,
                            MergedField.newMergedField(sel.field).build(),
                            // filter child plans to those that apply to the constrained type or root types
                            childPlans = sel.childPlans.filter {
                                state.constrainedTypes().contains(it.parentType) || it.parentType.isRootType(state.schema)
                            },
                            fieldTypeChildPlans = sel.fieldTypeChildPlans,
                            collectedFieldMetadata = sel.metadata
                        )
                    )

                sel is InlineFragment ->
                    // push all fragment fields onto the stack
                    queue.addAll(0, sel.selectionSet.selections)

                sel is FragmentSpread && sel.name in visitedFragments -> continue
                sel is FragmentSpread -> {
                    val def = state.fragmentDef(sel.name)
                    // push all fragment definition fields onto the stack
                    queue.addAll(0, def.selectionSet.selections)
                    visitedFragments += sel.name
                }

                else -> throw AssertionError("encountered unexpected state: $sel")
            }
        }

        return state.copy(
            acc = acc.toList(),
            pending = emptyList(),
            spreadFragments = visitedFragments
        )
    }

    private fun GraphQLOutputType.isRootType(schema: GraphQLSchema) =
        this == schema.queryType ||
            this == schema.mutationType ||
            this == schema.subscriptionType

    private fun merge(
        host: CollectedField,
        donor: CollectedField
    ): CollectedField {
        check((host.selectionSet == null) == (donor.selectionSet == null)) {
            "Cannot merge fields with different subselection flavors"
        }

        val newSelectionSet = host.selectionSet?.let { hss ->
            val dss = donor.selectionSet!!
            SelectionSet(
                selections = hss.selections + dss.selections
            )
        }
        return host.copy(
            mergedField = merge(host.mergedField, donor.mergedField),
            selectionSet = newSelectionSet
        )
    }

    private fun merge(
        host: MergedField,
        donor: MergedField
    ): MergedField = host.transform { it.fields(donor.fields) }
}
