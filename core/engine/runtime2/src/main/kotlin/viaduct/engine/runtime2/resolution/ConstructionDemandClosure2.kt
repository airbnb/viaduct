package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.concatenateSelectionForests
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.CheckerResolverBase
import viaduct.engine.runtime2.model.registry.ParentConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.instantiateVariables
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.guardedBy
import viaduct.engine.runtime2.resolution.framework.plus
import viaduct.engine.runtime2.resolution.framework.requiresStandardResolution

internal fun EngineObjectData.Sync.closeSelectedConstructionDemand(
    world: Assumptions,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: OrchestrationConstructionDemand<SelectionForest>,
): ClosedConstructionDemandContext =
    if (world.usePrecomputedConstructionDemand) {
        closePrecomputedConstructionDemand(world, objectOccurrence, queryOccurrence, initialDemand)
    } else {
        closeOrchestrationConstructionDemand(world, objectOccurrence, queryOccurrence, initialDemand)
    }

internal fun EngineObjectData.Sync.closePrecomputedConstructionDemand(
    world: Assumptions,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: OrchestrationConstructionDemand<SelectionForest>,
): ClosedConstructionDemandContext {
    require(schemaType(world.schema) == objectOccurrence.target.type)
    require(queryOccurrence.target.type == world.schema.requireQueryTypeDef())
    require(queryOccurrence.root === queryOccurrence.target && queryOccurrence.path.isEmpty())
    require(queryOccurrence.root !== objectOccurrence.root)
    val expansion = SymbolicExpansionState()
    val query = ClosureHelper(world, queryOccurrence, expansion)
    val objects = ClosureHelper(world, objectOccurrence, expansion, this, query)
    objects.retainInitial(initialDemand.objectRooted)
    query.retainInitial(initialDemand.queryRooted)
    while (objects.hasPendingDemand || query.hasPendingDemand) {
        objects.expand()
        query.expand()
    }
    return ClosedConstructionDemandContext(objects.finish(), query.finish())
}

private class ClosureHelper(
    private val world: Assumptions,
    private val occurrence: OEROccurrence,
    private val expansion: SymbolicExpansionState,
    private val source: EngineObjectData.Sync? = null,
    private val associatedQuery: ClosureHelper? = null,
) {
    private val registry = world.resolverRegistry
    private val query: ClosureHelper get() = associatedQuery ?: this
    private val contributions = mutableListOf<Demand<SelectionForest>>()
    private var pending = mutableListOf<Demand<SelectionForest>>()
    private val resolvers = linkedMapOf<ObjectEngineResult.ObjectKey, ResolverContext>()
    private val checkers = linkedMapOf<ObjectEngineResult.ObjectKey, CheckerContext>()
    private val typeChecker = if (source == null) null else occurrence.typeCheckerContext(world)
    private val requiresResolver: (ObjectEngineResult.ObjectKey) -> Boolean = source?.let { it::requiresStandardResolution } ?: { true }

    val hasPendingDemand: Boolean get() = pending.isNotEmpty()

    fun retainInitial(demand: Demand<SelectionForest>) {
        retain(demand + demand.liftPrecomputedParentConstructionDemand(world))
    }

    private fun retain(demand: Demand<SelectionForest>) {
        if (demand.checked.isEmpty() && demand.unchecked.isEmpty() && demand.typeCheckCondition === InclusionCondition.Never) return
        contributions += demand
        pending += demand
    }

    fun expand() {
        if (pending.isEmpty()) return
        val frontier = pending.combine()
        pending = mutableListOf()
        val resolverKeys = frontier.newResolverKeyInclusions(world, occurrence, resolvers, expansion, requiresResolver)
        val checkerKeys = frontier.newCheckerKeyInclusions(world, occurrence, checkers, expansion)
        resolverKeys.forEach { (key, condition) ->
            val context = resolvers.getValue(key)
            val template = requireNotNull(registry.precomputedConstructionDemand(key.field))
            val id = context.resolverOccurrenceId
            val input = if (associatedQuery == null) requireNotNull(template.queryRootDemand) else template.objectDemand
            addInputs(input.reusableDemand, input.templatedDemand, input.reusableParentDemand, input.templatedParentDemand, id, condition, checked = true)
            if (associatedQuery != null) {
                val queryInput = template.queryDemand
                query.addInputs(queryInput.reusableDemand, queryInput.templatedDemand, queryInput.reusableParentDemand, queryInput.templatedParentDemand, id, condition, checked = true)
            }
        }
        checkerKeys.forEach { (key, condition) ->
            val context = checkers.getValue(key)
            addCheckerInputs(context.checker, context.fragments.objectFragment.resolverOccurrenceId, condition)
        }
        typeChecker.addInclusion(frontier.typeCheckCondition)?.let { condition ->
            val context = requireNotNull(typeChecker)
            addCheckerInputs(context.checker, context.fragments.objectFragment.resolverOccurrenceId, condition)
        }
    }

    private fun addCheckerInputs(
        checker: CheckerResolverBase<*>,
        id: ResolverOccurrenceId,
        condition: InclusionCondition
    ) {
        val parents = registry.checkerParentDemand(checker)
        val template = checker.constructionDemandTemplate
        addInputs(template.staticObjectDemand, template.dynamicObjectDemand, parents.staticObjectParentDemand, parents.dynamicObjectParentDemand, id, condition, checked = false)
        query.addInputs(template.staticQueryDemand, template.dynamicQueryDemand, parents.staticQueryParentDemand, parents.dynamicQueryParentDemand, id, condition, checked = false)
    }

    private fun addInputs(
        static: SelectionForest,
        residual: SelectionForest,
        staticParents: ParentConstructionDemandTemplate,
        residualParents: ParentConstructionDemandTemplate,
        id: ResolverOccurrenceId,
        condition: InclusionCondition,
        checked: Boolean,
    ) {
        val selections = (static + residual.instantiateVariables(id)).guardedBy(condition)
        val demand = if (checked) Demand.checked(selections, typeCheckDemanded = false) else Demand.unchecked(selections)
        val parents = (staticParents.parentDemand() + residualParents.parentDemand().instantiateAt(id)).guardedBy(condition)
        retain(demand + parents)
    }

    fun finish(): ClosedOERConstructionDemandContext =
        finalizeClosedOERConstructionDemand(
            world,
            source,
            occurrence,
            contributions.combine(),
            checkers,
            typeChecker?.toOccurrence(occurrence, query.occurrence),
            resolvers,
            requiresResolver,
            occurrence.target,
            query.occurrence.target,
        )
}

private fun List<Demand<SelectionForest>>.combine(): Demand<SelectionForest> =
    if (isEmpty()) {
        Demand.EMPTY
    } else if (size == 1) {
        single()
    } else {
        Demand(
            map { it.checked }.concatenateSelectionForests(),
            map { it.unchecked }.concatenateSelectionForests(),
            any { it.typeCheckDemanded },
            InclusionCondition.anyOf(map { it.typeCheckCondition }),
        )
    }

private fun Demand<SelectionForest>.instantiateAt(id: ResolverOccurrenceId): Demand<SelectionForest> =
    if (this === Demand.EMPTY) {
        this
    } else {
        Demand(
            checked.instantiateVariables(id),
            unchecked.instantiateVariables(id),
            typeCheckDemanded,
            typeCheckCondition.mapVariables { it.instantiate(id) },
        )
    }
