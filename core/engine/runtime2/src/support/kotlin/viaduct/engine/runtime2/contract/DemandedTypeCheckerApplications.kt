package viaduct.engine.runtime2.contract

import java.util.IdentityHashMap
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.QueryOERValidationState
import viaduct.engine.runtime2.correctresolution.ResolverApplicationCache
import viaduct.engine.runtime2.correctresolution.completedOutputDemand
import viaduct.engine.runtime2.correctresolution.reapplyResolver
import viaduct.engine.runtime2.correctresolution.reapplyRootFieldReference
import viaduct.engine.runtime2.correctresolution.rootFieldReferenceWitness
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverOutputData
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.ResolverFragments
import viaduct.engine.runtime2.model.registry.ResolverTarget
import viaduct.engine.runtime2.model.satisfiableAlternatives
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.findStoredKey
import viaduct.engine.runtime2.resolution.framework.groundedArguments

/**
 * Reconstructs checked demand from the requested selections, registry fragments, and replayed source
 * ownership. Only independently reached resolver owners contribute checked inputs. Neither observed
 * resolver calls nor checker slots/calls seed demand. Query/reference observations locate occurrences;
 * source replay justifies references before their inputs enter the frontier.
 */
internal fun ObjectEngineResult.demandedTypeCheckerApplications(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
): Set<CheckerInvocationObservation> = TypeCheckerDemandOracle(operation, this).expected(selections)

private class TypeCheckerDemandOracle(
    private val operation: SharedOperationContext<*>,
    private val primary: ObjectEngineResult,
) {
    private val registry = operation.world.resolverRegistry
    private val resolverObserver = operation.resolverObserver as CorrectnessResolverObserver
    private val checkerObserver = operation.checkerObserver as CorrectnessCheckerObserver
    private val references = resolverObserver.rootFieldReferenceInvocations()
    private val addresses = IdentityHashMap<ObjectEngineResult, CheckedObjectAddress>()
    private val queryValidation = QueryOERValidationState()
    private val referenceWitness = operation.rootFieldReferenceWitness(primary)
    private val sources = mutableMapOf<ResolverOccurrenceId, ResolverOutputData?>()
    private val resolverInputs = mutableSetOf<ResolverOccurrenceId>()
    private val checkerInputs = mutableSetOf<Pair<ResolverTarget, ResolverOccurrenceId>>()
    private val expected = linkedSetOf<CheckerInvocationObservation>()

    fun expected(selections: SelectionForest): Set<CheckerInvocationObservation> {
        val roots = listOf(primary) + resolverObserver.allQueryOERs().keys + resolverObserver.allQueryFragmentResults().values.flatten() +
            checkerObserver.allQueryFragmentResults().values.flatten()
        roots.forEach { root -> index(root, root, emptyList()) { registry.createRootQueryInput() } }
        demand(primary, selections, checked = true)
        return expected
    }

    private fun cache(root: ObjectEngineResult): ResolverApplicationCache {
        // A resolver within an associated Query OER can read that same OER. Source replay must
        // share the correctness judgment's recursion-aware Query validation and replay caches.
        if (root !== primary) {
            check(queryValidation.validate(operation, root, selectionForestOf().merge(root.type), referenceWitness, selectionsAreChecked = false))
        }
        return queryValidation.replayCache(root, referenceWitness)
    }

    /** Indexing supplies structural addresses and lazy source relations, never additional demand. */
    private fun index(
        value: EngineResult?,
        root: ObjectEngineResult,
        path: List<PathComponent>,
        source: () -> ResolverOutputData?,
    ) {
        val resolvedSource by lazy {
            val raw = source()
            if (raw is RootFieldReferenceData) {
                requireNotNull(operation.reapplyRootFieldReference(cache(root), raw, root, path, value.completedOutputDemand())).output
            } else {
                raw
            }
        }
        when (value) {
            is ObjectEngineResult -> {
                if (addresses.containsKey(value)) return
                val address = CheckedObjectAddress(root, path, value) { resolvedSource as? EngineObjectData.Sync }
                addresses[value] = address
                value.keys.filterNot { it is ObjectEngineResult.ParentKey }.forEach { key ->
                    val cell = value.getCell(key)
                    if (cell.value.isCompleted) index(cell.value.get(), root, path + key) { sourceField(address, key) }
                }
            }
            is ListEngineResult -> value.forEachIndexed { index, cell ->
                if (cell.value.isCompleted) {
                    index(cell.value.get(), root, path + ListEngineResult.Index.of(index)) { (resolvedSource as? List<*>)?.get(index) }
                }
            }
            else -> Unit
        }
    }

    private fun sourceField(
        owner: CheckedObjectAddress,
        key: ObjectEngineResult.ObjectKey
    ): ResolverOutputData? {
        val id = ResolverOccurrenceId.at(owner.root, owner.path + key)
        if (sources.containsKey(id)) return sources[id]
        val source = owner.source()
        val output = when {
            source?.isPresent(key.field.name) == true -> source.outputValue(key.field.name)
            key.field in registry && key.groundedArguments(operation) is Arguments.Resolved ->
                requireNotNull(owner.result.reapplyResolver(operation, cache(owner.root), key, owner.path)) {
                    "Cannot reconstruct source for demanded resolver $id"
                }.output
            else -> null
        }
        sources[id] = output
        return output
    }

    private fun demand(
        value: EngineResult?,
        forest: SelectionForest,
        checked: Boolean,
        checkObject: Boolean = checked,
    ) {
        when (value) {
            is ObjectEngineResult -> {
                val owner = requireNotNull(addresses[value])
                if (checkObject) {
                    registry.typeChecker(value.type)?.let { checker ->
                        val observation = CheckerInvocationObservation(CheckerKind.TYPE, owner.root, owner.path, null, checker.target)
                        if (expected.add(observation)) {
                            demandCheckerInputs(owner, checker.target, owner.path, checker.instantiateFragmentsAt(owner.root, owner.path))
                        }
                    }
                }
                forest.merge(value.type).byKey().values.forEach { selection ->
                    // Filter at the owner edge before expanding either kind of input. This also
                    // covers symbolic owners whose arguments become Error before invocation.
                    if (!selection.inclusionCondition.hasIncludedAlternative()) return@forEach
                    val key = requireNotNull(value.findStoredKey(operation, selection.key))
                    val path = owner.path + key
                    if (key !is ObjectEngineResult.ParentKey) {
                        val canHaveInputs = key !is ObjectEngineResult.GroundKey || key.arguments is Arguments.Resolved
                        if (checked && canHaveInputs) {
                            registry.fieldChecker(key.field)?.let { checker ->
                                demandCheckerInputs(owner, checker.target, path, checker.instantiateFragmentsAt(owner.root, path))
                            }
                        }
                        val source = owner.source()
                        if (canHaveInputs && source?.isPresent(key.field.name) != true && key.field in registry) {
                            demandResolverInputs(owner, path)
                        }
                        demandReferenceInputs(owner, key)
                    }
                    demand(value.getCell(key).value.get(), selection.subselections, checked)
                }
            }
            is ListEngineResult -> value.forEach { demand(it.value.get(), forest, checked, checkObject) }
            else -> Unit
        }
    }

    /** A failed condition excludes its alternative, without suppressing another successful one. */
    private fun InclusionCondition.hasIncludedAlternative(): Boolean =
        satisfiableAlternatives().any { alternative ->
            when (alternative) {
                InclusionCondition.Always -> true
                is InclusionCondition.Requires -> alternative.values.all { (variable, required) ->
                    val binding = operation.variableBindings.getBinding(requireNotNull(variable.instanceId))
                    binding is VariableBinding.Input && binding.value == required
                }
                else -> false
            }
        }

    private fun demandResolverInputs(
        owner: CheckedObjectAddress,
        path: List<PathComponent>,
        reference: Boolean = false
    ) {
        val id = ResolverOccurrenceId.at(owner.root, path)
        if (!resolverInputs.add(id)) return
        val key = path.last() as ObjectEngineResult.ObjectKey
        val fragments = registry.resolver(key.field).instantiateFragmentsAt(owner.root, path)
        demand(owner.result, fragments.objectFragment.constructionSelections, checked = true, checkObject = false)
        if (!fragments.queryFragment.constructionSelections.isEmpty()) {
            val query = resolverObserver.queryFragmentResults(id).single()
            demand(query, fragments.queryFragment.constructionSelections, checked = true, checkObject = reference)
        }
    }

    private fun demandCheckerInputs(
        owner: CheckedObjectAddress,
        target: ResolverTarget,
        path: List<PathComponent>,
        fragments: ResolverFragments,
    ) {
        val id = ResolverOccurrenceId.at(owner.root, path)
        if (!checkerInputs.add(target to id)) return
        demand(owner.result, fragments.objectFragment.constructionSelections, checked = false)
        if (!fragments.queryFragment.constructionSelections.isEmpty()) {
            val query = resolverObserver.associatedQueryResult(owner.result) ?: checkerObserver.queryFragmentResults(target, id).single()
            demand(query, fragments.queryFragment.constructionSelections, checked = false)
        }
    }

    private fun demandReferenceInputs(
        owner: CheckedObjectAddress,
        key: ObjectEngineResult.ObjectKey
    ) {
        val fieldPath = owner.path + key
        if (references.none { it.publicationRoot === owner.root && it.publicationPath.take(fieldPath.size) == fieldPath }) return

        fun visit(
            value: EngineResult?,
            source: ResolverOutputData?,
            path: List<PathComponent>
        ) {
            if (source is RootFieldReferenceData) {
                // Replay validates the source target and every hop; observations only locate the
                // independent invocation roots of this demanded publication.
                requireNotNull(operation.reapplyRootFieldReference(cache(owner.root), source, owner.root, path, value.completedOutputDemand()))
                references.filter { it.publicationRoot === owner.root && it.publicationPath == path }.forEach { reference ->
                    val address = CheckedObjectAddress(reference.invocationRoot, emptyList(), reference.invocationRoot) { registry.createRootQueryInput() }
                    addresses.putIfAbsent(address.result, address)
                    demandResolverInputs(address, reference.invocationPath, reference = true)
                }
            } else if (value is ListEngineResult && source is List<*>) {
                value.forEachIndexed { index, cell -> visit(cell.value.get(), source[index], path + ListEngineResult.Index.of(index)) }
            }
        }
        visit(owner.result.getCell(key).value.get(), sourceField(owner, key), owner.path + key)
    }
}

private class CheckedObjectAddress(
    val root: ObjectEngineResult,
    val path: List<PathComponent>,
    val result: ObjectEngineResult,
    val source: () -> EngineObjectData.Sync?,
)
