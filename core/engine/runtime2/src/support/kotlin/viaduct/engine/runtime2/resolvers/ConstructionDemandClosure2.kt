package viaduct.engine.runtime2.resolvers

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.flatMerge
import viaduct.engine.runtime2.model.guardedBy
import viaduct.engine.runtime2.model.registry.CheckerResolverBase
import viaduct.engine.runtime2.model.registry.ConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.FragmentConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.ParentConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.instantiateVariables
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.OrchestrationConstructionDemand
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.argumentsContainErrorValue
import viaduct.engine.runtime2.resolution.framework.guardedBy
import viaduct.engine.runtime2.resolution.framework.plus
import viaduct.engine.runtime2.resolution.framework.requiresStandardResolution
import viaduct.engine.runtime2.resolution.liftPrecomputedParentConstructionDemand
import viaduct.engine.runtime2.resolution.parentDemand
import viaduct.graphql.schema.ViaductSchema

internal fun EngineObjectData.Sync.closePrecomputedConstructionDemand(
    operation: SharedOperationContext<*>,
    objectOccurrence: OEROccurrence,
    queryOccurrence: OEROccurrence,
    initialDemand: OrchestrationConstructionDemand<SelectionForest>,
): OrchestrationConstructionDemand<ObjectSelectionForest> {
    val objectType = schemaType(operation.world.schema)
    val world = operation.world
    val queryType = world.schema.requireQueryTypeDef()
    val groundedInitialDemand = OrchestrationConstructionDemand(
        objectRooted = (initialDemand.objectRooted + initialDemand.objectRooted.liftPrecomputedParentConstructionDemand(world)).applicableGroundSelections(operation, objectType),
        queryRooted = (initialDemand.queryRooted + initialDemand.queryRooted.liftPrecomputedParentConstructionDemand(world)).applicableGroundSelections(operation, queryType),
    )
    val queryClosure = ClosureHelper(operation, queryOccurrence, queryType, groundedInitialDemand.queryRooted)
    val objectClosure = ClosureHelper(operation, objectOccurrence, objectType, groundedInitialDemand.objectRooted, queryClosure, ::requiresStandardResolution)

    // Seed closure from the initial demanded keys: add their precomputed static dependencies and queue
    // resolver occurrences needing runtime variables or residual demand. Source-supplied object fields
    // suppress their resolver's input demand; the original checked/unchecked selections are retained.
    objectClosure.addInitialTemplates()
    queryClosure.addInitialTemplates()

    while (objectClosure.hasPendingDemand || queryClosure.hasPendingDemand) {
        // Bind argument/provider variables for this round's resolver occurrences before instantiating
        // either root's residual demand. These values depend on runtime arguments and provider results.
        objectClosure.bindVariables()
        queryClosure.bindVariables()

        // Instantiate the residuals that could not be closed statically: ground variable-bearing keys,
        // evaluate inclusion conditions, and merge keys that now coincide. Object resolvers can demand
        // both object and Query inputs; each contribution is merged into its respective root's demand.
        val dynamicObjectDemand = objectClosure.dynamicDemand()
        val dynamicQueryDemand = objectClosure.dynamicQueryDemand() + queryClosure.dynamicDemand()

        // Both roots' residuals have been consumed. Clear this round before addDemand queues the next,
        // so completed work is not repeated and newly discovered work survives for the next iteration.
        objectClosure.clearPendingKeys()
        queryClosure.clearPendingKeys()

        // Retain the grounded residual selections and expand newly discovered keys with their static
        // templates. Queue any further runtime residuals, continuing until neither root has pending work.
        objectClosure.addResidualDemand(dynamicObjectDemand)
        queryClosure.addResidualDemand(dynamicQueryDemand)
        objectClosure.addCheckerDemand()
        queryClosure.addCheckerDemand()
    }

    // Merge each provenance independently; checker inputs stay raw even when they activate resolvers.
    return OrchestrationConstructionDemand(
        objectRooted = objectClosure.closedDemand(),
        queryRooted = queryClosure.closedDemand(),
    )
}

private class ClosureHelper(
    private val operation: SharedOperationContext<*>,
    private val occurrence: OEROccurrence,
    private val type: ViaductSchema.Object,
    private val initialDemand: Demand<ObjectSelectionForest>,
    private val queryClosure: ClosureHelper? = null,
    private val requiresStandardResolution: (ObjectEngineResult.GroundKey) -> Boolean = { true },
) {
    private val registry = operation.world.resolverRegistry
    private val contributions = mutableListOf(this.initialDemand.checked)
    private val uncheckedContributions = mutableListOf(this.initialDemand.unchecked)
    private val checkerConditions = linkedMapOf<ObjectEngineResult.GroundKey, InclusionCondition>()
    private val pendingCheckers = linkedMapOf<ObjectEngineResult.GroundKey, InclusionCondition>()
    private val boundCheckerKeys = linkedSetOf<ObjectEngineResult.GroundKey>()
    private val typeChecker = if (queryClosure == null) null else registry.typeChecker(type)
    private var expandedTypeChecker = false
    private var typeCheckDemanded = initialDemand.typeCheckDemanded
    private val expandedFields = linkedSetOf<ViaductSchema.ObjectField>()
    private val discoveredKeys = linkedSetOf<ObjectEngineResult.GroundKey>()
    private val pendingKeys = linkedMapOf<ObjectEngineResult.GroundKey, ConstructionDemandTemplate>()

    val hasPendingDemand: Boolean
        get() = pendingKeys.isNotEmpty() || pendingCheckers.isNotEmpty() ||
            (!expandedTypeChecker && typeCheckDemanded && typeChecker != null)

    fun addInitialTemplates() {
        discoverCheckers(initialDemand.checked)
        // Seed discovery combines provenance; retained checked and unchecked selections remain separate.
        addTemplates(listOf(initialDemand.checked, initialDemand.unchecked).flatMerge(type).groundKeys())
    }

    private fun addDemand(
        demand: ObjectSelectionForest,
        checked: Boolean,
        parentDemand: Demand<SelectionForest>,
    ) {
        if (!demand.isEmpty()) {
            val parentKeys = retainDemand(demand, checked, parentDemand)
            addTemplates(demand.groundKeys() + parentKeys)
        }
    }

    private fun discoverCheckers(demand: ObjectSelectionForest) {
        demand.byKey().forEach { (key, selection) ->
            val groundKey = key as ObjectEngineResult.GroundKey
            if (!groundKey.arguments.argumentsContainErrorValue() && registry.fieldChecker(groundKey.field) != null) {
                discoverChecker(groundKey, selection.inclusionCondition)
            }
        }
    }

    private fun discoverChecker(
        key: ObjectEngineResult.GroundKey,
        condition: InclusionCondition
    ) {
        val previous = checkerConditions[key]
        val inclusion = previous?.or(condition) ?: condition
        if (previous != inclusion) {
            checkerConditions[key] = inclusion
            pendingCheckers[key] = inclusion
        }
    }

    private fun retainDemand(
        demand: ObjectSelectionForest,
        checked: Boolean,
        preparedParentDemand: Demand<SelectionForest>,
        staticCheckerKeys: Set<ObjectEngineResult.GroundKey>? = null,
    ): Set<ObjectEngineResult.GroundKey> {
        if (checked) {
            contributions += demand
            if (staticCheckerKeys == null) {
                discoverCheckers(demand)
            } else {
                staticCheckerKeys.forEach { discoverChecker(it, InclusionCondition.Always) }
            }
        } else {
            uncheckedContributions += demand
        }
        val parentDemand = preparedParentDemand.applicableGroundSelections(operation, type)
        contributions += parentDemand.checked
        uncheckedContributions += parentDemand.unchecked
        discoverCheckers(parentDemand.checked)
        typeCheckDemanded = typeCheckDemanded || parentDemand.typeCheckDemanded
        return parentDemand.checked.groundKeys() + parentDemand.unchecked.groundKeys()
    }

    private fun templateFor(key: ObjectEngineResult.GroundKey): ConstructionDemandTemplate =
        requireNotNull(registry.precomputedConstructionDemand(key.field)) {
            "Precomputed construction demand requires argument/provider variables: " +
                "${key.field.containingDef.name}/${key.field.name}"
        }

    private fun isResolverSeed(key: ObjectEngineResult.GroundKey): Boolean {
        if (key.arguments.argumentsContainErrorValue()) return false
        return key.field in registry
    }

    private fun localTemplate(template: ConstructionDemandTemplate): FragmentConstructionDemandTemplate = if (queryClosure == null) requireNotNull(template.queryRootDemand) else template.objectDemand

    // Dependency links preserve the supplied-field guard without re-merging forests to rediscover seeds.
    private fun addTemplates(keys: Collection<ObjectEngineResult.GroundKey>) {
        val frontier = ArrayDeque<Pair<ObjectEngineResult.GroundKey, ConstructionDemandTemplate?>>(keys.map { it to null })
        while (frontier.isNotEmpty()) {
            val (key, linkedTemplate) = frontier.removeFirst()
            if (!isResolverSeed(key) || !requiresStandardResolution(key) || !discoveredKeys.add(key)) continue
            val template = linkedTemplate ?: templateFor(key)
            val input = localTemplate(template)
            if (expandedFields.add(key.field)) {
                val parentKeys = retainDemand(input.reusableDemand, checked = true, preparedParentDemand = input.reusableParentDemand.parentDemand(), staticCheckerKeys = input.reusableCheckerKeys)
                frontier.addAll(input.dependencies.map { it.key to it.value })
                if (queryClosure != null) {
                    val queryInput = template.queryDemand
                    val queryParentKeys = queryClosure.retainDemand(
                        queryInput.reusableDemand,
                        checked = true,
                        preparedParentDemand = queryInput.reusableParentDemand.parentDemand(),
                        staticCheckerKeys = queryInput.reusableCheckerKeys
                    )
                    queryClosure.addTemplates(queryInput.dependencies.keys)
                    queryClosure.addTemplates(queryParentKeys)
                }
                frontier.addAll(parentKeys.map { it to null })
            }
            if (template.hasVariables || !input.templatedDemand.isEmpty() || (queryClosure != null && !template.queryDemand.templatedDemand.isEmpty())) {
                pendingKeys[key] = template
            }
        }
    }

    private fun ObjectSelectionForest.residualAt(
        key: ObjectEngineResult.GroundKey,
        parentTemplate: ParentConstructionDemandTemplate,
        target: ClosureHelper,
    ): ResidualDemand? {
        if (isEmpty()) return null
        val id = ResolverOccurrenceId.at(occurrence.root, occurrence.coordinate(key))
        return ResidualDemand(
            instantiateVariables(id).applicableGroundSelections(operation, target.type),
            parentTemplate.parentDemand().instantiateVariables(id),
        )
    }

    fun bindVariables() {
        pendingKeys.keys.bindResolverVariables(operation, occurrence.root, occurrence.path)
    }

    fun dynamicDemand(): List<ResidualDemand> =
        pendingKeys.mapNotNull { (key, template) ->
            val input = localTemplate(template)
            input.templatedDemand.residualAt(key, input.templatedParentDemand, this)
        }

    fun dynamicQueryDemand(): List<ResidualDemand> =
        pendingKeys.mapNotNull { (key, template) ->
            template.queryDemand.templatedDemand.residualAt(key, template.queryDemand.templatedParentDemand, requireNotNull(queryClosure))
        }

    fun addResidualDemand(residuals: List<ResidualDemand>) {
        val keys = linkedSetOf<ObjectEngineResult.GroundKey>()
        residuals.forEach { residual ->
            keys += residual.demand.groundKeys()
            keys += retainDemand(residual.demand, checked = true, preparedParentDemand = residual.parentDemand)
        }
        addTemplates(keys)
    }

    fun addCheckerDemand() {
        val checkers = pendingCheckers.toMap()
        pendingCheckers.clear()
        checkers.forEach { (key, inclusion) ->
            val checker = requireNotNull(registry.fieldChecker(key.field))
            val id = ResolverOccurrenceId.at(occurrence.root, occurrence.coordinate(key))
            if (checker.variables.isNotEmpty() && boundCheckerKeys.add(key)) {
                checker.instantiateFragments(id).bindFromArguments(operation, key.arguments as Arguments.Resolved)
            }
            addCheckerInputs(checker, id, inclusion)
        }
        if (queryClosure != null && typeCheckDemanded && !expandedTypeChecker) {
            expandedTypeChecker = true
            typeChecker?.let { checker ->
                addCheckerInputs(checker, ResolverOccurrenceId.at(occurrence.root, occurrence.path), InclusionCondition.Always)
            }
        }
    }

    private fun addCheckerInputs(
        checker: CheckerResolverBase<*>,
        id: ResolverOccurrenceId,
        inclusion: InclusionCondition,
    ) {
        val parents = registry.checkerParentDemand(checker)
        val template = requireNotNull(checker.constructionDemandTemplate) {
            "Precomputed checker demand requires argument-defined field variables or a variable-free type checker"
        }

        fun ClosureHelper.addInputs(
            staticDemand: ObjectSelectionForest,
            dynamicDemand: ObjectSelectionForest,
            staticParentDemand: ParentConstructionDemandTemplate,
            dynamicParentDemand: ParentConstructionDemandTemplate,
        ) {
            if (!staticDemand.isEmpty()) {
                val guardedDemand = if (inclusion === InclusionCondition.Always) staticDemand else staticDemand.guardedBy(inclusion).applicableGroundSelections(operation, type)
                addDemand(guardedDemand, checked = false, parentDemand = staticParentDemand.parentDemand().guardedBy(inclusion))
            }
            if (!dynamicDemand.isEmpty()) {
                addDemand(
                    dynamicDemand.instantiateVariables(id).guardedBy(inclusion).applicableGroundSelections(operation, type),
                    checked = false,
                    parentDemand = dynamicParentDemand.parentDemand().instantiateVariables(id).guardedBy(inclusion),
                )
            }
        }
        addInputs(template.staticObjectDemand, template.dynamicObjectDemand, parents.staticObjectParentDemand, parents.dynamicObjectParentDemand)
        (queryClosure ?: this).addInputs(template.staticQueryDemand, template.dynamicQueryDemand, parents.staticQueryParentDemand, parents.dynamicQueryParentDemand)
    }

    fun clearPendingKeys() {
        pendingKeys.clear()
    }

    fun closedDemand(): Demand<ObjectSelectionForest> =
        Demand(
            checked = contributions.flatMerge(type),
            unchecked = uncheckedContributions.flatMerge(type),
            typeCheckDemanded = typeCheckDemanded,
        )
}

private class ResidualDemand(
    val demand: ObjectSelectionForest,
    val parentDemand: Demand<SelectionForest>,
)

private fun Demand<SelectionForest>.instantiateVariables(id: ResolverOccurrenceId): Demand<SelectionForest> =
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
