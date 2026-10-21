package viaduct.engine.runtime2.resolution

import java.util.IdentityHashMap
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelection
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.concatenateSelectionForests
import viaduct.engine.runtime2.model.flatMapToSelectionForest
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.mapNotNullSelections
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
    val normalizer = ParentDemandNormalizer()
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
 * can transpose their demand onto the parent object. Requests are joined by canonical parent
 * field and normalized independently by checking provenance, rather than copied per path.
 */
private class ParentDemandAnalysis(
    val normalizer: ParentDemandNormalizer,
    val localDemand: Demand<SelectionForest> = Demand.EMPTY,
    val parentRequests: Map<ViaductSchema.ObjectField, ParentRequest> = emptyMap(),
    val reusable: Boolean = true,
) {
    operator fun plus(other: ParentDemandAnalysis): ParentDemandAnalysis =
        if (this === other) {
            this
        } else {
            ParentDemandAnalysis(
                normalizer,
                localDemand = normalizer.combine(localDemand, other.localDemand),
                parentRequests = normalizer.combineRequests(parentRequests, other.parentRequests),
                reusable = reusable && other.reusable,
            )
        }
}

internal class ParentRequest(
    val parentField: ViaductSchema.ObjectField,
    val demand: Demand<SelectionForest>,
)

/** Exact keys, applicability and provenance bound additions, including nested requirements. */
internal class ParentDemandNormalizer {
    // Forest identity only reuses immutable normalization within this lookahead computation.
    private val normalized = IdentityHashMap<SelectionForest, SelectionForest>()
    private val descendantGuardIds = IdentityHashMap<InclusionCondition, Int>()

    /** Join at the destination, retaining left-to-right guard order and separate provenance. */
    fun combineRequests(
        first: Map<ViaductSchema.ObjectField, ParentRequest>,
        second: Map<ViaductSchema.ObjectField, ParentRequest>,
    ): Map<ViaductSchema.ObjectField, ParentRequest> {
        if (first.isEmpty()) return second
        if (second.isEmpty() || first === second) return first
        val result = first.toMutableMap()
        second.forEach { (field, request) ->
            val existing = result[field]
            result[field] = if (existing == null || existing === request) request else ParentRequest(field, combine(existing.demand, request.demand))
        }
        return result
    }

    fun combine(
        first: Demand<SelectionForest>,
        second: Demand<SelectionForest>
    ): Demand<SelectionForest> {
        if (first === Demand.EMPTY) return second
        if (second === Demand.EMPTY || first === second) return first
        return Demand(
            checked = combine(first.checked, second.checked),
            unchecked = combine(first.unchecked, second.unchecked),
            typeCheckDemanded = first.typeCheckDemanded || second.typeCheckDemanded,
            typeCheckCondition = first.typeCheckCondition.or(second.typeCheckCondition),
        )
    }

    private fun combine(
        first: SelectionForest,
        second: SelectionForest
    ): SelectionForest =
        when {
            first === second || second.isEmpty() -> first
            first.isEmpty() -> second
            else -> normalize(first + second)
        }

    private data class RequirementId(
        val key: ObjectEngineResult.Key,
        val possibleTypes: Set<ViaductSchema.Object>,
        val descendantGuardId: Int?,
    )

    private fun normalize(forest: SelectionForest): SelectionForest {
        normalized[forest]?.let { return it }
        if (forest.isEmpty()) return forest
        val occurrences = linkedMapOf<RequirementId, MutableList<Selection>>()
        forest.forEach { selection ->
            // Boolean-equal guards can differ in binding order and must retain their descendants.
            val guardId = if (selection.subselections.isEmpty()) null else descendantGuardIds.getOrPut(selection.inclusionCondition) { descendantGuardIds.size }
            val id = RequirementId(selection.key, selection.possibleTypes, guardId)
            occurrences.getOrPut(id, ::mutableListOf).add(selection)
        }
        // Retain shared abstract selections; concrete specialization belongs to the later merge
        // against an occurrence's runtime type. Different applicability sets remain alternatives.
        val result = occurrences.entries.flatMapToSelectionForest { (id, alternatives) ->
            selectionForestOf(
                Selection.of(
                    key = id.key,
                    possibleTypes = id.possibleTypes,
                    inclusionCondition = InclusionCondition.anyOf(alternatives.map { it.inclusionCondition }),
                    subselections = normalize(
                        alternatives.map { it.subselections.guardedBy(it.inclusionCondition) }.concatenateSelectionForests(),
                    ),
                ),
            )
        }
        normalized[forest] = result
        normalized[result] = result
        return result
    }
}

private fun SelectionForest.findParentDemandInSelectionForest(
    world: Assumptions,
    context: ParentDemandContext,
    checked: Boolean,
): ParentDemandAnalysis {
    var result = ParentDemandAnalysis(context.normalizer)
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
            context.normalizer,
            parentRequests = mapOf(key.field to ParentRequest(key.field, parentDemand)),
        )
    }

    val typeInputs = if (checked && inclusionCondition !== InclusionCondition.Never) {
        (key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes
            .orEmpty().fold(ParentDemandAnalysis(context.normalizer)) { result, type ->
                result + type.findParentDemandInTypeFragment(world, context)
            }
    } else {
        ParentDemandAnalysis(context.normalizer)
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
    var parentRequests = emptyMap<ViaductSchema.ObjectField, ParentRequest>()
    nested.parentRequests.values.forEach { request ->
        val parentField = request.parentField
        val demand = request.demand.guardedBy(inclusionCondition)
        if (world.parentFieldRelations[parentField] == key.field) {
            val ancestor = demand.findParentDemandInDemand(world, context)
            localDemand = context.normalizer.combine(localDemand, context.normalizer.combine(demand, ancestor.localDemand))
            parentRequests = context.normalizer.combineRequests(parentRequests, ancestor.parentRequests)
            reusable = reusable && ancestor.reusable
        }
    }
    val field = key.field
    val fixedInputs =
        field.findParentDemandInObjectFragment(world, context, checked = true) +
            if (checked) {
                field.findParentDemandInObjectFragment(world, context, checked = false)
            } else {
                ParentDemandAnalysis(context.normalizer)
            }
    val guardedFixedInputs = fixedInputs.guardedBy(inclusionCondition)
    localDemand = context.normalizer.combine(localDemand, guardedFixedInputs.localDemand)
    parentRequests = context.normalizer.combineRequests(parentRequests, guardedFixedInputs.parentRequests)
    reusable = reusable && guardedFixedInputs.reusable
    return ParentDemandAnalysis(context.normalizer, localDemand, parentRequests, reusable)
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
        } ?: return ParentDemandAnalysis(context.normalizer)
    if (!context.expandingObjectFragmentIds.add(objectFragmentId)) {
        return ParentDemandAnalysis(context.normalizer, reusable = false)
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
    if (condition === InclusionCondition.Always) {
        this
    } else {
        ParentDemandAnalysis(
            normalizer,
            localDemand = localDemand.guardedBy(condition),
            parentRequests = parentRequests.mapValues { (_, request) -> ParentRequest(request.parentField, request.demand.guardedBy(condition)) },
            reusable = reusable,
        )
    }

/** Fixed descendant demand is lifted before occurrence-local condition bindings can exist. */
internal fun SelectionForest.withoutInclusionConditions(): SelectionForest =
    mapNotNullSelections { selection ->
        if (selection.inclusionCondition === InclusionCondition.Never) {
            null
        } else {
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                inclusionCondition = InclusionCondition.Always,
                subselections = selection.subselections.withoutInclusionConditions(),
            )
        }
    }

/** Type-checker inputs cross an unchecked boundary, with a separate recursion cut from fields. */
private fun ViaductSchema.Object.findParentDemandInTypeFragment(
    world: Assumptions,
    context: ParentDemandContext,
): ParentDemandAnalysis {
    context.parentDemandByType[this]?.let { return it }
    val checker = context.typeChecker(this) ?: return ParentDemandAnalysis(context.normalizer)
    if (!context.expandingTypes.add(this)) return ParentDemandAnalysis(context.normalizer, reusable = false)
    val result = try {
        checker.objectFragment.withoutInclusionConditions().findParentDemandInSelectionForest(world, context, checked = false)
    } finally {
        context.expandingTypes.remove(this)
    }
    if (result.reusable) context.parentDemandByType[this] = result
    return result
}
