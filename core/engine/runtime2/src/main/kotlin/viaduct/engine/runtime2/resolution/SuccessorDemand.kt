package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.containsErrorValue
import viaduct.engine.runtime2.model.containsParentKeys
import viaduct.engine.runtime2.model.flatMapToSelectionForest
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.mapSelections
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.PreparedParentDemand
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.plus
import viaduct.graphql.schema.ViaductSchema

/** Producer-facing values for ordinary checked selections. */
internal fun SelectionForest.successorDemand(world: Assumptions): SelectionForest = Demand.checked(this).successorDemandFromConstructionDemand(world)

/**
 * Retains checked/unchecked provenance while expanding fixed inputs, as in grounded successor demand.
 * Parent construction lookahead and parent successor lifting are fused into the successor demand
 * traversal so the construction demand forest is walked once per provenance instead of four times.
 */
internal fun Demand<SelectionForest>.successorDemandFromConstructionDemand(
    world: Assumptions,
    possibleRootTypes: Set<ViaductSchema.Object> = emptySet(),
): SelectionForest {
    val context = FusedSuccessorDemandContext(world)
    val checkedResult = checked.fusedSuccessorDemandWithChecks(context, checked = true)
    val uncheckedResult = unchecked.fusedSuccessorDemandWithChecks(context, checked = false)
    val typeCheckerDemand = if (typeCheckCondition !== InclusionCondition.Never) {
        possibleRootTypes.fixedTypeCheckerInputDemand(context.expansionContext).guardedBy(typeCheckCondition)
    } else {
        selectionForestOf()
    }
    val topLevelParent = context.parentApplication.combine(checkedResult.parentDemand, uncheckedResult.parentDemand)
    val topLevelParentSuccessor =
        if (topLevelParent === PreparedParentDemand.EMPTY) {
            selectionForestOf()
        } else {
            val parentLocal = topLevelParent.localDemand
            parentLocal.checked.fusedSuccessorDemandWithChecks(context, checked = true).successorDemand +
                parentLocal.unchecked.fusedSuccessorDemandWithChecks(context, checked = false).successorDemand
        }
    return checkedResult.successorDemand + uncheckedResult.successorDemand + typeCheckerDemand + topLevelParentSuccessor
}

/** Implemented only by prepared registries; decorating a registry must not inherit its cache. */
internal interface FixedSuccessorDemandCacheOwner {
    val fixedSuccessorDemandCache: FixedSuccessorDemandCache
}

/** Registry-lifetime memoization of immutable, recursion-stack-independent template expansions. */
internal class FixedSuccessorDemandCache {
    private val demands = ConcurrentHashMap<SuccessorBoundary, SelectionForest>()

    internal fun get(boundary: SuccessorBoundary): SelectionForest? = demands[boundary]

    internal fun put(
        boundary: SuccessorBoundary,
        demand: SelectionForest,
    ) {
        demands.putIfAbsent(boundary, demand)
    }
}

private class FusedSuccessorDemandContext(
    val world: Assumptions,
) {
    val expansionContext = SuccessorDemandContext(world.resolverRegistry, world.parentFieldRelations)
    val parentApplication = ParentDemandApplication(world)
    val hasParentRelations = world.parentFieldRelations.isNotEmpty()
}

/** Recursion state belongs to one computation, even when completed expansions are shared. */
private class SuccessorDemandContext(
    val registry: ResolverRegistry,
    val parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField>,
) {
    val hasParentRelations = parentFieldRelations.isNotEmpty()
    val expansionState = SuccessorExpansionState((registry as? FixedSuccessorDemandCacheOwner)?.fixedSuccessorDemandCache)
}

/** Memoizes only expansions independent of the current recursion stack. */
private class SuccessorExpansionState(
    private val sharedCache: FixedSuccessorDemandCache?,
) {
    private val expandingBoundaries = mutableSetOf<SuccessorBoundary>()
    private val fixedInputDemand = mutableMapOf<SuccessorBoundary, SelectionForest>()
    private var cycleCuts = 0

    fun cachedInputDemand(boundary: SuccessorBoundary): SelectionForest? = sharedCache?.get(boundary) ?: fixedInputDemand[boundary]

    fun beginExpansion(boundary: SuccessorBoundary): Int? {
        if (expandingBoundaries.add(boundary)) return cycleCuts
        cycleCuts++
        return null
    }

    fun endExpansion(boundary: SuccessorBoundary) {
        expandingBoundaries.remove(boundary)
    }

    fun cacheInputDemand(
        boundary: SuccessorBoundary,
        cutsBefore: Int,
        demand: SelectionForest
    ) {
        if (cycleCuts == cutsBefore) {
            if (sharedCache != null) sharedCache.put(boundary, demand) else fixedInputDemand[boundary] = demand
        }
    }
}

/** Unlike grounded boundaries, fixed-template boundaries do not depend on argument values. */
internal data class SuccessorBoundary(
    val field: ViaductSchema.ObjectField? = null,
    val type: ViaductSchema.Object? = null,
    val kind: SuccessorBoundaryKind,
)

internal enum class SuccessorBoundaryKind {
    RESOLVER,
    CHECKER,
    TYPE_CHECKER,
}

private class FusedSuccessorResult(
    val successorDemand: SelectionForest,
    val parentDemand: PreparedParentDemand,
    val successorContainsParentKeys: Boolean,
)

private val EMPTY_FUSED_RESULT = FusedSuccessorResult(selectionForestOf(), PreparedParentDemand.EMPTY, false)

/**
 * Fused successor demand traversal: walks the construction demand once, simultaneously computing
 * successor demand (walk 2/3), integrating parent construction demand from the transition table
 * (walk 1), and resolving ParentKey selections in the successor demand (walk 4).
 */
private fun SelectionForest.fusedSuccessorDemandWithChecks(
    context: FusedSuccessorDemandContext,
    checked: Boolean,
    producerSuppliableOnly: Boolean = false,
): FusedSuccessorResult {
    if (isEmpty()) return EMPTY_FUSED_RESULT
    var combinedParentDemand = PreparedParentDemand.EMPTY
    var anyParentKeys = false
    val successorDemand = flatMap { selection ->
        var selectionParent = PreparedParentDemand.EMPTY
        val successor = selection.possibleTypes.flatMapToSelectionForest { type ->
            val key = selection.objectKey(type)
            val requested = selection.fusedRequestedSuccessorDemand(context, key, type, checked, producerSuppliableOnly)
            selectionParent = context.parentApplication.combine(selectionParent, requested.parentDemand)
            val fixedDemand = selection.fixedSuccessorInputDemand(context.expansionContext, key, checked)
            anyParentKeys = anyParentKeys || requested.successorContainsParentKeys || fixedDemand.containsParentKeys()
            requested.successorDemand + fixedDemand
        }

        // Walk 1: resolve parent transition at this selection
        val parentInduced =
            if (!producerSuppliableOnly && context.hasParentRelations) {
                resolveParentTransition(context, selection, selectionParent, checked)
            } else {
                null
            }
        if (parentInduced != null) {
            selectionParent = parentInduced.remainingParentDemand
        }
        combinedParentDemand = context.parentApplication.combine(combinedParentDemand, selectionParent)
        if (parentInduced != null) successor + parentInduced.inducedSuccessorDemand else successor
    }.compactSuccessorDemand()
    return FusedSuccessorResult(successorDemand, combinedParentDemand, anyParentKeys)
}

private class ParentTransitionResult(
    val inducedSuccessorDemand: SelectionForest,
    val remainingParentDemand: PreparedParentDemand,
)

private fun resolveParentTransition(
    context: FusedSuccessorDemandContext,
    selection: Selection,
    nestedParentDemand: PreparedParentDemand,
    checked: Boolean,
): ParentTransitionResult? {
    var anyTransition = false
    var combinedParentResult = PreparedParentDemand.EMPTY
    selection.possibleTypes.forEach { type ->
        val transition = context.world.resolverRegistry.parentSelectionDemand(type)[selection.key.field.name] ?: return@forEach
        anyTransition = true
        val typeInputs = if (checked && selection.inclusionCondition !== InclusionCondition.Never) transition.typeCheckers else PreparedParentDemand.EMPTY
        val nested = context.parentApplication.combine(nestedParentDemand, typeInputs)
        val fixed = if (checked) transition.checked else transition.unchecked
        if (nested !== PreparedParentDemand.EMPTY || fixed !== PreparedParentDemand.EMPTY) {
            val concrete = Selection.of(selection.objectKey(type), setOf(type), selection.subselections, selection.inclusionCondition)
            combinedParentResult = context.parentApplication.combine(combinedParentResult, context.parentApplication.apply(concrete, checked, nested, fixed))
        }
    }
    if (!anyTransition) return null
    val localDemand = combinedParentResult.localDemand
    val inducedSuccessor =
        if (localDemand === Demand.EMPTY) {
            selectionForestOf()
        } else {
            localDemand.checked.fusedSuccessorDemandWithChecks(context, checked = true).successorDemand +
                localDemand.unchecked.fusedSuccessorDemandWithChecks(context, checked = false).successorDemand
        }
    return ParentTransitionResult(inducedSuccessor, parentDemandOf(requests = combinedParentResult.requests))
}

/** Preserve requested metadata, including excluded selections, where the producer can supply it. */
private fun Selection.fusedRequestedSuccessorDemand(
    context: FusedSuccessorDemandContext,
    key: ObjectEngineResult.ObjectKey,
    type: ViaductSchema.Object,
    checked: Boolean,
    producerSuppliableOnly: Boolean,
): FusedSuccessorResult {
    val hasRegisteredResolver = key.field in context.expansionContext.registry
    if (hasRegisteredResolver && (key !is ObjectEngineResult.GroundKey || (producerSuppliableOnly && key.field.args.isNotEmpty()))) {
        return EMPTY_FUSED_RESULT
    }
    check(key is ObjectEngineResult.GroundKey) { "Resolution found open arguments on passive key $key" }
    val typeCheckerInputs = if (checked && inclusionCondition !== InclusionCondition.Never) {
        (key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes
            ?.fixedTypeCheckerInputDemand(context.expansionContext) ?: selectionForestOf()
    } else {
        selectionForestOf()
    }
    val nestedResult = subselections.fusedSuccessorDemandWithChecks(context, checked, producerSuppliableOnly)
    val nestedHasParentKeys = nestedResult.successorContainsParentKeys || typeCheckerInputs.containsParentKeys()
    val nestedSuccessor = nestedResult.successorDemand + typeCheckerInputs

    val successorDemand = rootAndLiftSuccessorDemand(
        context.world.parentFieldRelations,
        context.hasParentRelations,
        key,
        type,
        nestedSuccessor,
        nestedHasParentKeys,
    )
    return FusedSuccessorResult(
        successorDemand,
        nestedResult.parentDemand,
        key is ObjectEngineResult.ParentKey || nestedHasParentKeys,
    )
}

/**
 * Shared composition for both fused and non-fused successor demand: resolves walk-4 ParentKey
 * selections, builds the rooted producer selection, and guards the lifted demand. Both
 * requestedSuccessorDemand and fusedRequestedSuccessorDemand delegate here to avoid drift.
 */
private fun Selection.rootAndLiftSuccessorDemand(
    parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField>,
    hasParentRelations: Boolean,
    key: ObjectEngineResult.GroundKey,
    type: ViaductSchema.Object,
    nestedDemand: SelectionForest,
    nestedContainsParentKeys: Boolean,
): SelectionForest {
    val lifted =
        if (hasParentRelations && nestedContainsParentKeys) {
            resolveParentKeySelections(parentFieldRelations, key.field, type, nestedDemand)
        } else {
            selectionForestOf()
        }
    val rootedSelection = Selection.of(
        key = key,
        possibleTypes = setOf(key.field.containingDef),
        subselections = nestedDemand,
        inclusionCondition = inclusionCondition,
    )
    return selectionForestOf(rootedSelection) + lifted.guardedBy(inclusionCondition)
}

private fun resolveParentKeySelections(
    parentFieldRelations: Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField>,
    producerField: ViaductSchema.ObjectField,
    producerType: ViaductSchema.Object,
    nestedSuccessor: SelectionForest,
): SelectionForest {
    val childType = producerField.type.baseTypeDef as? ViaductSchema.Object ?: return selectionForestOf()
    val merged = nestedSuccessor.merge(childType)
    var lifted = selectionForestOf()
    merged.byKey().values.forEach { childSelection ->
        val parentKey = childSelection.key as? ObjectEngineResult.ParentKey ?: return@forEach
        if (parentFieldRelations[parentKey.field] == producerType.requireField(producerField.name)) {
            lifted = lifted + childSelection.subselections
        }
    }
    return lifted
}

private fun Selection.fixedSuccessorInputDemand(
    context: SuccessorDemandContext,
    key: ObjectEngineResult.ObjectKey,
    checked: Boolean,
): SelectionForest {
    if (inclusionCondition === InclusionCondition.Never || key.arguments.containsErrorValue()) {
        return selectionForestOf()
    }
    val resolverInputs = key.field.fixedResolverInputDemand(context)
    val checkerInputs = if (checked) key.field.fixedCheckerInputDemand(context) else selectionForestOf()
    return (resolverInputs + checkerInputs).guardedBy(inclusionCondition)
}

private fun ViaductSchema.ObjectField.fixedResolverInputDemand(context: SuccessorDemandContext): SelectionForest =
    SuccessorBoundary(field = this, kind = SuccessorBoundaryKind.RESOLVER).fixedInputDemand(context)

private fun ViaductSchema.ObjectField.fixedCheckerInputDemand(context: SuccessorDemandContext): SelectionForest =
    SuccessorBoundary(field = this, kind = SuccessorBoundaryKind.CHECKER).fixedInputDemand(context)

private fun Set<ViaductSchema.Object>.fixedTypeCheckerInputDemand(context: SuccessorDemandContext): SelectionForest =
    flatMapToSelectionForest { type ->
        SuccessorBoundary(type = type, kind = SuccessorBoundaryKind.TYPE_CHECKER).fixedInputDemand(context)
    }

private fun SuccessorBoundary.fixedInputDemand(context: SuccessorDemandContext): SelectionForest {
    context.expansionState.cachedInputDemand(this)?.let { return it }
    val cutsBefore = context.expansionState.beginExpansion(this) ?: return selectionForestOf()
    val result = try {
        val fragment = when (kind) {
            SuccessorBoundaryKind.RESOLVER ->
                if (requireNotNull(field) in context.registry) context.registry.resolver(field).objectFragment else null
            SuccessorBoundaryKind.CHECKER -> context.registry.fieldChecker(requireNotNull(field))?.objectFragment
            SuccessorBoundaryKind.TYPE_CHECKER -> context.registry.typeChecker(requireNotNull(type))?.objectFragment
        }
        // Descendant occurrence bindings do not exist while choosing a producer's passive output.
        fragment?.withoutInclusionConditions()?.successorDemandWithChecks(
            context,
            checked = kind == SuccessorBoundaryKind.RESOLVER,
            producerSuppliableOnly = true,
        ) ?: selectionForestOf()
    } finally {
        context.expansionState.endExpansion(this)
    }
    // A cycle-cut result depends on the current stack and cannot be reused elsewhere.
    context.expansionState.cacheInputDemand(this, cutsBefore, result)
    return result
}

/**
 * Non-fused successor demand for fixed fragment expansion. Fixed fragments use the existing
 * expansion cache and do not need parent transition integration (their parent demand is
 * already captured in the transition table).
 */
private fun SelectionForest.successorDemandWithChecks(
    context: SuccessorDemandContext,
    checked: Boolean,
    producerSuppliableOnly: Boolean = false,
): SelectionForest =
    flatMap { selection ->
        selection.possibleTypes.flatMapToSelectionForest { type ->
            val key = selection.objectKey(type)
            selection.requestedSuccessorDemand(context, key, type, checked, producerSuppliableOnly) +
                selection.fixedSuccessorInputDemand(context, key, checked)
        }
    }.compactSuccessorDemand()

private fun Selection.requestedSuccessorDemand(
    context: SuccessorDemandContext,
    key: ObjectEngineResult.ObjectKey,
    type: ViaductSchema.Object,
    checked: Boolean,
    producerSuppliableOnly: Boolean,
): SelectionForest {
    val hasRegisteredResolver = key.field in context.registry
    if (hasRegisteredResolver && (key !is ObjectEngineResult.GroundKey || (producerSuppliableOnly && key.field.args.isNotEmpty()))) {
        return selectionForestOf()
    }
    check(key is ObjectEngineResult.GroundKey) { "Resolution found open arguments on passive key $key" }
    val typeCheckerInputs = if (checked && inclusionCondition !== InclusionCondition.Never) {
        (key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes
            ?.fixedTypeCheckerInputDemand(context) ?: selectionForestOf()
    } else {
        selectionForestOf()
    }
    val nestedDemand = subselections.successorDemandWithChecks(context, checked, producerSuppliableOnly) + typeCheckerInputs

    return rootAndLiftSuccessorDemand(
        context.parentFieldRelations,
        context.hasParentRelations,
        key,
        type,
        nestedDemand,
        nestedDemand.containsParentKeys(),
    )
}

/**
 * Fixed resolver/checker fragments can reach the same passive field along many dependency paths.
 * Normalize each concrete branch before reusing it, including descendants, so those paths do not
 * become an exponentially duplicated producer forest. Merging pushes each alternative's guard
 * into its own descendants and preserves distinct symbolic keys.
 */
private fun SelectionForest.compactSuccessorDemand(): SelectionForest {
    if (isEmpty()) return this
    if (size == 1) {
        val selection = single()
        val compactedChildren = selection.subselections.compactSuccessorDemand()
        return if (compactedChildren === selection.subselections) {
            this
        } else {
            selectionForestOf(
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    inclusionCondition = selection.inclusionCondition,
                    subselections = compactedChildren,
                ),
            )
        }
    }
    val types = linkedSetOf<ViaductSchema.Object>()
    forEach { types += it.possibleTypes }
    if (types.size == 1) {
        val keys = linkedSetOf<ObjectEngineResult.Key>()
        forEach { keys += it.key }
        if (keys.size == size) {
            return mapSelections { selection ->
                Selection.of(
                    key = selection.key,
                    possibleTypes = selection.possibleTypes,
                    inclusionCondition = selection.inclusionCondition,
                    subselections = selection.subselections.compactSuccessorDemand(),
                )
            }
        }
    }
    return types.flatMapToSelectionForest { type ->
        merge(type).mapSelections { selection ->
            Selection.of(
                key = selection.key,
                possibleTypes = selection.possibleTypes,
                inclusionCondition = selection.inclusionCondition,
                subselections = selection.subselections.compactSuccessorDemand(),
            )
        }
    }
}
