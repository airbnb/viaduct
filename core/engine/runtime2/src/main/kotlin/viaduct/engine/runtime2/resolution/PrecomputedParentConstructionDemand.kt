package viaduct.engine.runtime2.resolution

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.containsVariables
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.ParentConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.ParentDemandContinuation
import viaduct.engine.runtime2.model.registry.ParentSelectionDemand
import viaduct.engine.runtime2.model.registry.PreparedParentDemand
import viaduct.engine.runtime2.model.registry.PreparedParentRequest
import viaduct.engine.runtime2.model.registry.PreparedParentSelection
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.Demand
import viaduct.engine.runtime2.resolution.framework.guardedBy
import viaduct.graphql.schema.ViaductSchema

/** Runtime demand supplies occurrence paths; fixed resolver/checker bodies are already prepared. */
internal fun Demand<SelectionForest>.liftPrecomputedParentConstructionDemand(
    world: Assumptions,
    engineExecutionContext: EngineExecutionContext?,
    operationDemandCache: OperationDemandCache? = null,
): Demand<SelectionForest> =
    if (checked.isEmpty() && unchecked.isEmpty()) {
        Demand.EMPTY
    } else {
        ParentDemandMatcher(world, engineExecutionContext, operationDemandCache).let { matcher ->
            matcher.application.combine(
                matcher.demand(checked, checked = true),
                matcher.demand(unchecked, checked = false),
            ).localDemand
        }
    }

internal fun ParentConstructionDemandTemplate.parentDemand(): Demand<SelectionForest> = summary.localDemand

internal class ParentDemandApplication(
    private val world: Assumptions,
    private val engineExecutionContext: EngineExecutionContext?,
    private val useExecutionSelection: Boolean = true,
    private val operationDemandCache: OperationDemandCache? = null,
) {
    private val normalizer by lazy(LazyThreadSafetyMode.NONE) { ParentDemandNormalizer() }

    private val evaluated by lazy(LazyThreadSafetyMode.NONE) {
        operationDemandCache?.parentDemands ?: mutableMapOf<ParentConstructionDemandTemplate, PreparedParentDemand>()
    }
    private val evaluating by lazy(LazyThreadSafetyMode.NONE) { mutableSetOf<ParentConstructionDemandTemplate>() }
    private var cycleCuts = 0

    fun evaluate(program: ParentConstructionDemandTemplate): PreparedParentDemand {
        if (!program.mayInduceParentDemand) return PreparedParentDemand.EMPTY
        if (!useExecutionSelection || !program.dependsOnExecutionSelection) return program.summary
        evaluated[program]?.let { return it }
        if (!evaluating.add(program)) {
            cycleCuts++
            return PreparedParentDemand.EMPTY
        }
        val cutsBefore = cycleCuts
        val result = try {
            summarize(program)
        } finally {
            evaluating.remove(program)
        }
        if (cutsBefore == cycleCuts) evaluated.putIfAbsent(program, result)
        return result
    }

    fun summarize(program: ParentConstructionDemandTemplate): PreparedParentDemand =
        if (!program.mayInduceParentDemand) {
            PreparedParentDemand.EMPTY
        } else {
            program.selections.fold(PreparedParentDemand.EMPTY) { result, selection -> combine(result, evaluate(selection)) }
        }

    private fun evaluate(prepared: PreparedParentSelection): PreparedParentDemand {
        val selection = prepared.selection
        val condition = selection.inclusionCondition
        if (prepared.parentProducer != null) {
            val demand = if (prepared.checked) Demand.checked(selection.subselections).guardedBy(condition) else Demand.unchecked(selection.subselections).guardedBy(condition)
            return parentDemandOf(
                requests = mapOf(
                    selection.key.field to PreparedParentRequest(parentDemandOf(demand), listOf(ParentDemandContinuation(prepared.children, condition))),
                )
            )
        }
        val nested = prepared.typeCheckers.fold(evaluate(prepared.children)) { result, checker -> combine(result, evaluate(checker)) }
        val inputs = prepared.resolver?.let { if (useExecutionSelection) it.dependenciesFor(engineExecutionContext) else it.declaredDependencies }
        val fixed = listOfNotNull(inputs?.objectParentDemand, prepared.checker).fold(PreparedParentDemand.EMPTY) { result, boundary -> combine(result, evaluate(boundary)) }
        return apply(selection, prepared.checked, nested, fixed)
    }

    fun fixedInputs(
        transition: ParentSelectionDemand,
        checked: Boolean
    ): PreparedParentDemand {
        val inputs = transition.resolver?.dependenciesFor(engineExecutionContext)
        val resolverProgram = inputs?.objectParentDemand
        if ((inputs == null || inputs === transition.resolver.declaredDependencies) &&
            resolverProgram?.dependsOnExecutionSelection != true && (!checked || transition.checker?.dependsOnExecutionSelection != true)
        ) {
            return if (checked) transition.checked else transition.unchecked
        }
        val resolver = resolverProgram?.let(::evaluate) ?: PreparedParentDemand.EMPTY
        return if (checked) combine(resolver, transition.checker?.let(::evaluate) ?: PreparedParentDemand.EMPTY) else resolver
    }

    fun typeInputs(transition: ParentSelectionDemand): PreparedParentDemand =
        if (transition.typeCheckerPrograms.none { it.dependsOnExecutionSelection }) {
            transition.typeCheckers
        } else {
            transition.typeCheckerPrograms.fold(PreparedParentDemand.EMPTY) { result, checker -> combine(result, evaluate(checker)) }
        }

    fun apply(
        selection: Selection,
        checked: Boolean,
        nested: PreparedParentDemand,
        fixed: PreparedParentDemand,
    ): PreparedParentDemand {
        val condition = selection.inclusionCondition

        fun carry(forest: SelectionForest): SelectionForest = if (forest.isEmpty()) selectionForestOf() else selectionForestOf(Selection.of(selection.key, selection.possibleTypes, forest, condition))

        var result = parentDemandOf(
            localDemand = if (checked) {
                Demand(carry(nested.localDemand.checked), carry(nested.localDemand.unchecked), typeCheckDemanded = false)
            } else {
                Demand.unchecked(carry(nested.localDemand.values))
            },
        )
        nested.requests.forEach { (parent, request) ->
            if (world.parentFieldRelations[parent] == selection.key.field) {
                val guardedRequest = request.guardedBy(condition)
                val ancestor = guardedRequest.continuations.fold(parentDemandOf()) { accumulated, continuation ->
                    combine(accumulated, evaluate(continuation.program).guardedBy(continuation.condition))
                }
                result = combine(result, parentDemandOf(normalizer.combine(guardedRequest.demand.localDemand, ancestor.localDemand), ancestor.requests))
            }
        }
        return combine(result, fixed.reusableInputs().guardedBy(condition))
    }

    fun combine(
        first: PreparedParentDemand,
        second: PreparedParentDemand
    ): PreparedParentDemand {
        if (first === PreparedParentDemand.EMPTY) return second
        if (second === PreparedParentDemand.EMPTY || first === second) return first
        val requests = first.requests.toMutableMap()
        second.requests.forEach { (field, request) ->
            val previous = requests[field]
            requests[field] = if (previous == null || previous === request) {
                request
            } else {
                PreparedParentRequest(
                    parentDemandOf(normalizer.combine(previous.demand.localDemand, request.demand.localDemand)),
                    (previous.continuations + request.continuations).distinct(),
                )
            }
        }
        return parentDemandOf(normalizer.combine(first.localDemand, second.localDemand), requests)
    }
}

private class ParentDemandMatcher(
    private val world: Assumptions,
    engineExecutionContext: EngineExecutionContext?,
    operationDemandCache: OperationDemandCache?,
) {
    val application = ParentDemandApplication(world, engineExecutionContext, operationDemandCache = operationDemandCache)

    fun demand(
        forest: SelectionForest,
        checked: Boolean,
    ): PreparedParentDemand {
        var result = PreparedParentDemand.EMPTY
        forest.forEach { selection ->
            selection.possibleTypes.forEach { type ->
                val transition = world.resolverRegistry.parentSelectionDemand(type)[selection.key.field.name]
                if (transition != null) {
                    val typeInputs = if (checked && selection.inclusionCondition !== InclusionCondition.Never) application.typeInputs(transition) else PreparedParentDemand.EMPTY
                    val nested = application.combine(demand(selection.subselections, checked), typeInputs)
                    val fixed = application.fixedInputs(transition, checked)
                    if (nested !== PreparedParentDemand.EMPTY || fixed !== PreparedParentDemand.EMPTY) {
                        val concrete = Selection.of(selection.objectKey(type), setOf(type), selection.subselections, selection.inclusionCondition)
                        result = application.combine(result, application.apply(concrete, checked, nested, fixed))
                    }
                }
            }
        }
        return result
    }
}

private fun PreparedParentRequest.guardedBy(condition: InclusionCondition): PreparedParentRequest =
    if (condition === InclusionCondition.Always) {
        this
    } else {
        PreparedParentRequest(
            demand.guardedBy(condition),
            continuations.map { it.copy(condition = condition.and(it.condition)) },
        )
    }

private fun PreparedParentDemand.guardedBy(condition: InclusionCondition): PreparedParentDemand =
    if (condition === InclusionCondition.Always) {
        this
    } else {
        parentDemandOf(
            localDemand.guardedBy(condition),
            requests.mapValues { (_, request) -> request.guardedBy(condition) },
        )
    }

internal fun parentDemandOf(
    localDemand: Demand<SelectionForest> = Demand.EMPTY,
    requests: Map<ViaductSchema.ObjectField, PreparedParentRequest> = emptyMap(),
): PreparedParentDemand =
    if (localDemand === Demand.EMPTY && requests.isEmpty()) {
        PreparedParentDemand.EMPTY
    } else {
        PreparedParentDemand(
            localDemand.checked,
            localDemand.unchecked,
            localDemand.typeCheckDemanded,
            localDemand.typeCheckCondition,
            requests,
        )
    }

internal val PreparedParentDemand.localDemand: Demand<SelectionForest>
    get() = if (this === PreparedParentDemand.EMPTY) Demand.EMPTY else Demand(checkedDemand, uncheckedDemand, typeCheckDemanded, typeCheckCondition)

private fun PreparedParentDemand.reusableInputs(): PreparedParentDemand =
    if (!checkedDemand.containsVariables() && !uncheckedDemand.containsVariables()) this else parentDemandOf(localDemand.reusableParentDemand(), requests)
