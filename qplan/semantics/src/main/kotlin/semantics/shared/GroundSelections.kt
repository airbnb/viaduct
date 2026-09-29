package semantics.shared

import model.Arguments
import model.InclusionCondition
import model.ObjectEngineResult
import model.ObjectSelection
import model.ObjectSelectionForest
import model.SelectionForest
import model.concatenateSelectionForests
import model.guardedBy
import model.merge
import viaduct.graphql.schema.ViaductSchema

/** Grounds top-level keys and coalesces selections whose keys become equal. */
fun ObjectSelectionForest.instantiateBindings(operation: SharedOperationContext<*>): ObjectSelectionForest =
    groundSelections { selection ->
        selection.key.arguments.instantiateBindings(operation, selection.key.field)
    }

/** Specializes this demand to [type] and grounds its top-level keys. */
fun SelectionForest.applicableGroundSelections(
    operation: SharedOperationContext<*>,
    type: ViaductSchema.Object,
): ObjectSelectionForest = merge(type).instantiateBindings(operation)

/** Specializes and grounds checked and unchecked demand independently. */
internal fun Demand<SelectionForest>.applicableGroundSelections(
    operation: SharedOperationContext<*>,
    type: ViaductSchema.Object,
): Demand<ObjectSelectionForest> =
    Demand(
        checked = checked.applicableGroundSelections(operation, type),
        unchecked = unchecked.applicableGroundSelections(operation, type),
        typeCheckDemanded = typeCheckDemanded,
    )

private inline fun ObjectSelectionForest.groundSelections(groundArguments: (ObjectSelection) -> Arguments.Ground): ObjectSelectionForest {
    val selectionsByKey =
        buildMap<ObjectEngineResult.GroundKey, MutableList<ObjectSelection>> {
            byKey().values.forEach { selection ->
                val key =
                    ObjectEngineResult.GroundKey.of(
                        field = selection.key.field,
                        arguments = groundArguments(selection),
                    )
                getOrPut(key, ::mutableListOf).add(selection)
            }
        }
    return ObjectSelectionForest.of(
        type = type,
        selections =
            selectionsByKey.map { (key, selections) ->
                ObjectSelection.of(
                    key = key,
                    possibleTypes = setOf(type),
                    inclusionCondition =
                        InclusionCondition.anyOf(
                            selections.map { selection -> selection.inclusionCondition },
                        ),
                    subselections =
                        selections
                            .map { selection ->
                                selection.subselections.guardedBy(selection.inclusionCondition)
                            }.concatenateSelectionForests(),
                )
            },
    )
}
