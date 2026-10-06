package viaduct.engine.runtime2.resolution

import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.guardedBy
import viaduct.engine.runtime2.resolution.framework.plus
import viaduct.graphql.schema.ViaductSchema

/**
 * Returns additional construction demand induced by parent selections in requested descendants
 * and in the fixed inputs of resolver and checker boundaries reached from those descendants. The
 * caller adds this contribution to its original demand; the two may overlap.
 *
 * The worked examples in `ParentConstructionDemandTest` are the best introduction to this
 * operation, especially its recursive lifting through intermediate OERs and resolver inputs.
 */
internal fun SelectionForest.liftParentConstructionDemand(world: Assumptions): SelectionForest =
    if (world.parentFieldRelations.isEmpty()) {
        selectionForestOf()
    } else {
        findParentDemandInSelectionForest(
            world,
            ParentDemandContext(fieldChecker = { null }),
            checked = true,
        ).localDemand.values
    }

/** Preserves checked and unchecked provenance while lifting additional parent demand. */
internal fun Demand<SelectionForest>.liftParentConstructionDemand(world: Assumptions): Demand<SelectionForest> =
    if (world.parentFieldRelations.isEmpty()) {
        Demand.EMPTY
    } else {
        val context = ParentDemandContext(world.resolverRegistry::fieldChecker, world.resolverRegistry::typeChecker)
        (
            checked.findParentDemandInSelectionForest(world, context, checked = true) +
                unchecked.findParentDemandInSelectionForest(world, context, checked = false)
        ).localDemand
    }

/** Memoization and cycle detection are local to one parent-demand computation. */
private class ParentDemandContext(
    val fieldChecker: (ViaductSchema.ObjectField) -> FieldCheckerResolver?,
    val typeChecker: (ViaductSchema.Object) -> TypeCheckerResolver? = { null },
) {
    val expandingTypes = mutableSetOf<ViaductSchema.Object>()
    val parentDemandByType = mutableMapOf<ViaductSchema.Object, ParentDemandAnalysis>()
    val parentDemandByObjectFragmentId = mutableMapOf<ObjectFragmentId, ParentDemandAnalysis>()
    val expandingObjectFragmentIds = mutableSetOf<ObjectFragmentId>()
}

private data class ObjectFragmentId(
    val field: ViaductSchema.ObjectField,
    val checked: Boolean,
)

/**
 * Intermediate result of lifting parent-induced demand through a selection tree.
 *
 * [localDemand] is additional demand already placed at the current object's level.
 * [parentRequests] are demands reached through `@parent` fields whose matching producer field
 * has not yet been encountered; enclosing selections carry them outward until that producer edge
 * can transpose their demand onto the parent object.
 */
private class ParentDemandAnalysis(
    val localDemand: Demand<SelectionForest> = Demand.EMPTY,
    val parentRequests: List<ParentRequest> = emptyList(),
    val reusable: Boolean = true,
) {
    operator fun plus(other: ParentDemandAnalysis): ParentDemandAnalysis =
        ParentDemandAnalysis(
            localDemand = localDemand + other.localDemand,
            parentRequests = parentRequests + other.parentRequests,
            reusable = reusable && other.reusable,
        )
}

private class ParentRequest(
    val parentField: ViaductSchema.ObjectField,
    val demand: Demand<SelectionForest>,
)

private fun SelectionForest.findParentDemandInSelectionForest(
    world: Assumptions,
    context: ParentDemandContext,
    checked: Boolean,
): ParentDemandAnalysis {
    var result = ParentDemandAnalysis()
    forEach { selection ->
        // An abstract field can be a parent link on one implementation and ordinary on another.
        // Specialize before choosing either case, preserving each contribution's type condition.
        selection.possibleTypes.forEach { type ->
            result +=
                Selection.of(
                    key = selection.objectKey(type),
                    possibleTypes = setOf(type),
                    subselections = selection.subselections,
                    inclusionCondition = selection.inclusionCondition,
                ).findParentDemandInObjectSelection(
                    world,
                    context,
                    checked,
                )
        }
    }
    return result
}

private fun ObjectSelection.findParentDemandInObjectSelection(
    world: Assumptions,
    context: ParentDemandContext,
    checked: Boolean,
): ParentDemandAnalysis {
    if (key is ObjectEngineResult.ParentKey) {
        val parentDemand =
            if (checked) {
                Demand.checked(subselections).guardedBy(inclusionCondition)
            } else {
                Demand.unchecked(subselections.guardedBy(inclusionCondition))
            }
        return ParentDemandAnalysis(
            parentRequests =
                listOf(
                    ParentRequest(
                        key.field,
                        parentDemand,
                    ),
                ),
        )
    }

    val typeInputs = if (checked && inclusionCondition !== InclusionCondition.Never) {
        (key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes
            .orEmpty().fold(ParentDemandAnalysis()) { result, type ->
                result + type.findParentDemandInTypeFragment(world, context)
            }
    } else {
        ParentDemandAnalysis()
    }
    val nested = subselections.findParentDemandInSelectionForest(world, context, checked) + typeInputs

    fun carryNestedDemand(demand: SelectionForest): SelectionForest =
        if (demand.isEmpty()) {
            selectionForestOf()
        } else {
            selectionForestOf(
                Selection.of(
                    key = key,
                    possibleTypes = possibleTypes,
                    subselections = demand,
                    inclusionCondition = inclusionCondition,
                ),
            )
        }
    var localDemand =
        if (checked) {
            Demand(
                checked = carryNestedDemand(nested.localDemand.checked),
                unchecked = carryNestedDemand(nested.localDemand.unchecked),
                typeCheckDemanded = false,
            )
        } else {
            // An unchecked ancestor is only transport. Checked work below it recovers checked
            // provenance when passive descent reaches its resolver boundary.
            Demand.unchecked(carryNestedDemand(nested.localDemand.values))
        }
    var reusable = nested.reusable
    val parentRequests = mutableListOf<ParentRequest>()
    nested.parentRequests.forEach { unguardedRequest ->
        val request =
            ParentRequest(
                parentField = unguardedRequest.parentField,
                demand = unguardedRequest.demand.guardedBy(inclusionCondition),
            )
        if (world.parentFieldRelations[request.parentField] == key.field) {
            val ancestor = request.demand.findParentDemandInDemand(world, context)
            localDemand += request.demand + ancestor.localDemand
            parentRequests += ancestor.parentRequests
            reusable = reusable && ancestor.reusable
        }
    }
    val field = key.field
    val fixedInputs =
        field.findParentDemandInObjectFragment(world, context, checked = true) +
            if (checked) {
                field.findParentDemandInObjectFragment(world, context, checked = false)
            } else {
                ParentDemandAnalysis()
            }
    val guardedFixedInputs = fixedInputs.guardedBy(inclusionCondition)
    localDemand += guardedFixedInputs.localDemand
    parentRequests += guardedFixedInputs.parentRequests
    reusable = reusable && guardedFixedInputs.reusable
    return ParentDemandAnalysis(localDemand, parentRequests, reusable)
}

private fun Demand<SelectionForest>.findParentDemandInDemand(
    world: Assumptions,
    context: ParentDemandContext,
): ParentDemandAnalysis =
    checked.findParentDemandInSelectionForest(world, context, checked = true) +
        unchecked.findParentDemandInSelectionForest(world, context, checked = false)

private fun ViaductSchema.ObjectField.findParentDemandInObjectFragment(
    world: Assumptions,
    context: ParentDemandContext,
    checked: Boolean,
): ParentDemandAnalysis {
    val objectFragmentId = ObjectFragmentId(this, checked)
    context.parentDemandByObjectFragmentId[objectFragmentId]?.let { return it }
    val selections =
        if (checked) {
            if (this in world.resolverRegistry) {
                world.resolverRegistry.resolver(this).objectFragment
            } else {
                null
            }
        } else {
            context.fieldChecker(this)?.objectFragment
        } ?: return ParentDemandAnalysis()
    if (!context.expandingObjectFragmentIds.add(objectFragmentId)) {
        return ParentDemandAnalysis(reusable = false)
    }
    val result = try {
        selections
            .withoutInclusionConditions()
            .findParentDemandInSelectionForest(world, context, checked)
    } finally {
        context.expandingObjectFragmentIds.remove(objectFragmentId)
    }
    if (result.reusable) context.parentDemandByObjectFragmentId[objectFragmentId] = result
    return result
}

private fun ParentDemandAnalysis.guardedBy(condition: InclusionCondition): ParentDemandAnalysis =
    ParentDemandAnalysis(
        localDemand = localDemand.guardedBy(condition),
        parentRequests =
            parentRequests.map { request ->
                ParentRequest(
                    parentField = request.parentField,
                    demand = request.demand.guardedBy(condition),
                )
            },
        reusable = reusable,
    )

/** Fixed descendant demand is lifted before occurrence-local condition bindings can exist. */
internal fun SelectionForest.withoutInclusionConditions(): SelectionForest =
    flatMap { selection ->
        if (selection.inclusionCondition === InclusionCondition.Never) {
            return@flatMap selectionForestOf()
        }
        selectionForestOf(
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                inclusionCondition = InclusionCondition.Always,
                subselections = selection.subselections.withoutInclusionConditions(),
            ),
        )
    }

/** Type-checker inputs cross an unchecked boundary, with a separate recursion cut from fields. */
private fun ViaductSchema.Object.findParentDemandInTypeFragment(
    world: Assumptions,
    context: ParentDemandContext,
): ParentDemandAnalysis {
    context.parentDemandByType[this]?.let { return it }
    val checker = context.typeChecker(this) ?: return ParentDemandAnalysis()
    if (!context.expandingTypes.add(this)) return ParentDemandAnalysis(reusable = false)
    val result = try {
        checker.objectFragment.withoutInclusionConditions().findParentDemandInSelectionForest(world, context, checked = false)
    } finally {
        context.expandingTypes.remove(this)
    }
    if (result.reusable) context.parentDemandByType[this] = result
    return result
}
