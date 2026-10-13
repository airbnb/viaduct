package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.containsErrorValue
import viaduct.engine.runtime2.model.flatMapToSelectionForest
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.liftParentSuccessorDemand
import viaduct.engine.runtime2.resolution.framework.plus
import viaduct.graphql.schema.ViaductSchema

/** Producer-facing values for ordinary checked selections. */
internal fun SelectionForest.successorDemand(world: Assumptions): SelectionForest = Demand.checked(this).successorDemandFromConstructionDemand(world)

/**
 * Retains checked/unchecked provenance while expanding fixed inputs, as in grounded successor demand.
 * Parent construction lookahead precedes symbolic projection so pruned resolver branches cannot
 * hide ancestor requirements. The resulting forest describes only producer-facing values.
 */
internal fun Demand<SelectionForest>.successorDemandFromConstructionDemand(
    world: Assumptions,
    possibleRootTypes: Set<ViaductSchema.Object> = emptySet(),
): SelectionForest {
    val constructionDemand = this + liftParentConstructionDemand(world)
    val context = SuccessorDemandContext(world.resolverRegistry)
    val checkedDemand = constructionDemand.checked.successorDemandWithChecks(context, checked = true)
    val uncheckedDemand = constructionDemand.unchecked.successorDemandWithChecks(context, checked = false)
    val typeCheckerDemand = if (constructionDemand.typeCheckCondition !== InclusionCondition.Never) {
        possibleRootTypes.fixedTypeCheckerInputDemand(context).guardedBy(constructionDemand.typeCheckCondition)
    } else {
        selectionForestOf()
    }
    val demand = checkedDemand + uncheckedDemand + typeCheckerDemand
    return demand + demand.liftParentSuccessorDemand(world)
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

/** Recursion state belongs to one computation, even when completed expansions are shared. */
private class SuccessorDemandContext(
    val registry: ResolverRegistry,
) {
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

private fun SelectionForest.successorDemandWithChecks(
    context: SuccessorDemandContext,
    checked: Boolean,
    producerSuppliableOnly: Boolean = false,
): SelectionForest =
    flatMap { selection ->
        selection.possibleTypes.flatMapToSelectionForest { type ->
            val key = selection.objectKey(type)
            selection.requestedSuccessorDemand(context, key, checked, producerSuppliableOnly) +
                selection.fixedSuccessorInputDemand(context, key, checked)
        }
    }.compactSuccessorDemand()

/** Preserve requested metadata, including excluded selections, where the producer can supply it. */
private fun Selection.requestedSuccessorDemand(
    context: SuccessorDemandContext,
    key: ObjectEngineResult.ObjectKey,
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
    val rootedSelection = Selection.of(
        key = key,
        possibleTypes = setOf(key.field.containingDef),
        subselections = nestedDemand,
        inclusionCondition = inclusionCondition,
    )
    return selectionForestOf(rootedSelection)
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
