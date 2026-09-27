package semantics.resolver26

import model.Assumptions
import model.InclusionCondition
import model.ObjectEngineResult
import model.Selection
import model.SelectionForest
import model.containsErrorValue
import model.flatMapToSelectionForest
import model.guardedBy
import model.merge
import model.objectKey
import model.selectionForestOf
import semantics.shared.Demand
import semantics.shared.liftParentSuccessorDemand
import semantics.shared.plus
import viaduct.graphql.schema.ViaductSchema

/** Producer-facing values for ordinary checked selections. */
internal fun SelectionForest.successorDemand(world: Assumptions): SelectionForest =
    Demand.checked(this).successorDemandFromConstructionDemand(world)

/**
 * Retains checked/unchecked provenance while expanding fixed inputs, as in grounded successor demand.
 * Parent construction lookahead precedes symbolic projection so pruned resolver branches cannot
 * hide ancestor requirements. The resulting forest describes only producer-facing values.
 */
internal fun Demand<SelectionForest>.successorDemandFromConstructionDemand(world: Assumptions): SelectionForest {
    val constructionDemand = this + liftParentConstructionDemand(world)
    val context = SuccessorDemandContext(world)
    val checkedDemand = constructionDemand.checked.successorDemandWithChecks(context, checked = true)
    val uncheckedDemand = constructionDemand.unchecked.successorDemandWithChecks(context, checked = false)
    val demand = checkedDemand + uncheckedDemand
    return demand + demand.liftParentSuccessorDemand(world)
}

/** Per-computation recursion and memoization for argument-independent fixed-template analysis. */
private class SuccessorDemandContext(
    val world: Assumptions,
) {
    val expansionState = SuccessorExpansionState()
}

/** Memoizes only expansions independent of the current recursion stack. */
private class SuccessorExpansionState {
    private val expandingBoundaries = mutableSetOf<SuccessorBoundary>()
    private val fixedInputDemand = mutableMapOf<SuccessorBoundary, SelectionForest>()
    private var cycleCuts = 0

    fun cachedInputDemand(boundary: SuccessorBoundary): SelectionForest? = fixedInputDemand[boundary]

    fun beginExpansion(boundary: SuccessorBoundary): Int? {
        if (expandingBoundaries.add(boundary)) return cycleCuts
        cycleCuts++
        return null
    }

    fun endExpansion(boundary: SuccessorBoundary) {
        expandingBoundaries.remove(boundary)
    }

    fun cacheInputDemand(boundary: SuccessorBoundary, cutsBefore: Int, demand: SelectionForest) {
        if (cycleCuts == cutsBefore) fixedInputDemand[boundary] = demand
    }
}

/** Unlike grounded boundaries, fixed-template boundaries do not depend on argument values. */
private data class SuccessorBoundary(
    val field: ViaductSchema.ObjectField,
    val kind: SuccessorBoundaryKind,
)

private enum class SuccessorBoundaryKind {
    RESOLVER,
    CHECKER,
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
    val hasRegisteredResolver = key.field in context.world.resolverRegistry
    if (hasRegisteredResolver && (key !is ObjectEngineResult.GroundKey || (producerSuppliableOnly && key.field.args.isNotEmpty()))) {
        return selectionForestOf()
    }
    check(key is ObjectEngineResult.GroundKey) { "Resolver26 found open arguments on passive key $key" }
    val nestedDemand = subselections.successorDemandWithChecks(context, checked, producerSuppliableOnly)
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
    SuccessorBoundary(this, SuccessorBoundaryKind.RESOLVER).fixedInputDemand(context)

private fun ViaductSchema.ObjectField.fixedCheckerInputDemand(context: SuccessorDemandContext): SelectionForest =
    SuccessorBoundary(this, SuccessorBoundaryKind.CHECKER).fixedInputDemand(context)

private fun SuccessorBoundary.fixedInputDemand(context: SuccessorDemandContext): SelectionForest {
    context.expansionState.cachedInputDemand(this)?.let { return it }
    val fragment = when (kind) {
        SuccessorBoundaryKind.RESOLVER ->
            if (field in context.world.resolverRegistry) context.world.resolverRegistry.resolver(field).objectFragment else null
        SuccessorBoundaryKind.CHECKER -> context.world.resolverRegistry.fieldChecker(field)?.objectFragment
    } ?: return selectionForestOf()
    val cutsBefore = context.expansionState.beginExpansion(this) ?: return selectionForestOf()
    val result = try {
        // Descendant occurrence bindings do not exist while choosing a producer's passive output.
        fragment.withoutInclusionConditions().successorDemandWithChecks(
            context,
            checked = kind == SuccessorBoundaryKind.RESOLVER,
            producerSuppliableOnly = true,
        )
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
