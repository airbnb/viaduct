package semantics.resolver26

import viaduct.graphql.schema.ViaductSchema

import model.ObjectEngineResult

import model.Assumptions
import model.Selection
import model.SelectionForest
import model.containsErrorValue
import model.flatMapToSelectionForest
import model.objectKey
import model.selectionForestOf
import semantics.shared.liftParentSuccessorDemand
import model.InclusionCondition
import model.guardedBy
import model.merge
import semantics.shared.Demand
import semantics.shared.plus

/** Producer-facing values; checker provenance is retained only while expanding fixed inputs. */
internal fun SelectionForest.successorDemand(world: Assumptions): SelectionForest = Demand.checked(this).successorDemand(world)

internal fun Demand<SelectionForest>.successorDemand(world: Assumptions): SelectionForest {
    val demand = this + liftParentConstructionDemand(world)
    val logic = SuccessorDemandLogic(world)
    val values = logic.expand(demand.checked, checked = true) + logic.expand(demand.unchecked, checked = false)
    return values + values.liftParentSuccessorDemand(world)
}

/** Fixed fragments can revisit their own field through raw demand; the boundary kind matters. */
private data class InputBoundary(
    val field: ViaductSchema.ObjectField,
    val checker: Boolean
)

private class SuccessorDemandLogic(
    private val world: Assumptions
) {
    private val expanding = mutableSetOf<InputBoundary>()
    private val fixedDemand = mutableMapOf<InputBoundary, SelectionForest>()
    private var cycleCuts = 0

    fun expand(
        selections: SelectionForest,
        checked: Boolean,
        passiveOnly: Boolean = false
    ): SelectionForest =
        selections
            .flatMap { selection ->
                selection.possibleTypes.flatMapToSelectionForest { type ->
                    val key = selection.objectKey(type)
                    val active = key.field in world.resolverRegistry
                    val requested =
                        if (active && (key !is ObjectEngineResult.GroundKey || (passiveOnly && key.field.args.isNotEmpty()))) {
                            selectionForestOf()
                        } else {
                            check(key is ObjectEngineResult.GroundKey) { "Resolver26 found open arguments on passive key $key" }
                            selectionForestOf(
                                Selection.of(
                                    key = key,
                                    possibleTypes = setOf(type),
                                    inclusionCondition = selection.inclusionCondition,
                                    subselections = expand(selection.subselections, checked, passiveOnly),
                                ),
                            )
                        }
                    val inputs =
                        if (selection.inclusionCondition === InclusionCondition.Never || key.arguments.containsErrorValue()) {
                            selectionForestOf()
                        } else {
                            fixedInputs(key.field, checker = false) +
                                if (checked) fixedInputs(key.field, checker = true) else selectionForestOf()
                        }
                    requested + inputs.guardedBy(selection.inclusionCondition)
                }
            }.compactSuccessorDemand()

    private fun fixedInputs(
        field: ViaductSchema.ObjectField,
        checker: Boolean
    ): SelectionForest {
        val boundary = InputBoundary(field, checker)
        fixedDemand[boundary]?.let { return it }
        val fragment =
            if (checker) {
                world.resolverRegistry.fieldChecker(field)?.objectFragment
            } else if (field in world.resolverRegistry) {
                world.resolverRegistry.resolver(field).objectFragment
            } else {
                null
            }
        if (fragment == null) return selectionForestOf()
        if (!expanding.add(boundary)) {
            cycleCuts++
            return selectionForestOf()
        }
        val cutsBefore = cycleCuts
        val result = try {
            // Occurrence-local bindings do not exist while choosing a producer's passive output.
            expand(fragment.withoutInclusionConditions(), checked = !checker, passiveOnly = true)
        } finally {
            expanding.remove(boundary)
        }
        // A cycle-cut result depends on the current stack and cannot be reused elsewhere.
        if (cycleCuts == cutsBefore) fixedDemand[boundary] = result
        return result
    }
}

/**
 * Fixed resolver/checker fragments can reach the same passive field along many dependency paths.
 * Normalize each concrete branch before reusing it, including descendants, so those paths do not
 * become an exponentially duplicated producer forest. Merging pushes each alternative's guard
 * into its own descendants and preserves distinct symbolic keys.
 */
private fun SelectionForest.compactSuccessorDemand(): SelectionForest {
    val types = linkedSetOf<ViaductSchema.Object>()
    forEach { types += it.possibleTypes }
    return types.flatMapToSelectionForest { type ->
        merge(type).flatMap { selection ->
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    inclusionCondition = selection.inclusionCondition,
                    subselections = selection.subselections.compactSuccessorDemand(),
                ),
            )
        }
    }
}
