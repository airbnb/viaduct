package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.flatMapToSelectionForest
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.graphql.schema.ViaductSchema

/**
 * Returns additional successor demand lifted from parent selections already present in this forest.
 * The caller adds this contribution to its original demand; the two may overlap.
 *
 * Recursion combines original child selections with their lifted additions before lifting across
 * the next producer boundary, so grandparent selections continue to propagate outward. Only the
 * additions and their containing field paths are returned, with their inclusion conditions intact.
 */
internal fun SelectionForest.liftParentSuccessorDemand(world: Assumptions): SelectionForest =
    flatMap { selection ->
        val nestedAdditions = selection.subselections.liftParentSuccessorDemand(world)
        val nestedDemand = selection.subselections + nestedAdditions
        val nestedContribution =
            if (nestedAdditions.isEmpty()) {
                selectionForestOf()
            } else {
                selectionForestOf(
                    Selection.of(
                        key = selection.key,
                        possibleTypes = selection.possibleTypes,
                        subselections = nestedAdditions,
                        inclusionCondition = selection.inclusionCondition,
                    ),
                )
            }
        val lifted =
            selection.possibleTypes.flatMapToSelectionForest { possibleType ->
                val producer = possibleType.requireField(selection.key.field.name)
                val childType = producer.type.baseTypeDef as? ViaductSchema.Object
                    ?: return@flatMapToSelectionForest selectionForestOf()
                nestedDemand
                    .merge(childType)
                    .byKey()
                    .values
                    .filter { childSelection ->
                        val parentKey = childSelection.key as? ObjectEngineResult.ParentKey
                        parentKey != null &&
                            world.parentFieldRelations[parentKey.field] == producer
                    }
                    .fold(selectionForestOf()) { demand, parentSelection ->
                        demand + parentSelection.subselections
                    }
            }
        nestedContribution + lifted.guardedBy(selection.inclusionCondition)
    }
