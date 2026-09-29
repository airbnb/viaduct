package semantics.contract

import model.EngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.ResolverOccurrenceId
import model.SelectionForest
import model.merge
import model.objectKey
import semantics.arbitrary.GeneratedTypeCheckerMode
import semantics.arbitrary.ResolverTestRun
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.CheckerKind
import viaduct.engine.api.CheckerResult

/** Runtime evidence, qualified by root identity and the entire path (including list indices). */
internal enum class GeneratedTypeCheckerSignature {
    TYPE_CHECKER,
    SUCCESS,
    DENIAL,
    LIST_OCCURRENCE,
    ASSOCIATED_QUERY_OCCURRENCE,
    REPEATED_TYPE,
    OBJECT_INPUT_RESOLVER,
    QUERY_INPUT_RESOLVER,
    LIST_OCCURRENCE_WITH_RESOLVER_INPUT,
    ASSOCIATED_QUERY_WITH_RESOLVER_INPUT,
    SUCCESS_WITH_RESOLVER_INPUT,
    DENIAL_WITH_RESOLVER_INPUT,
    RESOLVER_INPUT_FIELD_CHECK,
    RESOLVER_INPUT_TYPE_CHECK,
    FIELD_AND_TYPE_SAME_PATH,
}

internal class GeneratedTypeCheckerCoverage {
    private val counts = GeneratedTypeCheckerSignature.entries.associateWithTo(linkedMapOf()) { 0 }

    fun record(execution: GeneratedResolutionObservation) {
        execution.typeCheckerCoverage().forEach { signature -> counts[signature] = counts.getValue(signature) + 1 }
    }

    fun assertRequired(
        run: ResolverTestRun,
        mode: GeneratedTypeCheckerMode
    ) {
        val required = GeneratedTypeCheckerSignature.entries.toSet() - when (mode) {
            GeneratedTypeCheckerMode.SUCCESS -> setOf(GeneratedTypeCheckerSignature.DENIAL, GeneratedTypeCheckerSignature.DENIAL_WITH_RESOLVER_INPUT)
            GeneratedTypeCheckerMode.DENIAL -> setOf(GeneratedTypeCheckerSignature.SUCCESS, GeneratedTypeCheckerSignature.SUCCESS_WITH_RESOLVER_INPUT)
            else -> emptySet()
        }
        val missing = required.filter { counts.getValue(it) == 0 }
        run.assertAggregate(missing.isEmpty(), "Type-checker profile missed activated coverage $missing; ${summary()}")
    }

    fun summary(): String = "activatedCases=$counts"
}

/** Registration alone, or a resolver invoked at another occurrence, cannot satisfy these guards. */
internal fun GeneratedResolutionObservation.typeCheckerCoverage(): Set<GeneratedTypeCheckerSignature> {
    val types = checkerApplications.filter { it.checkerKind == CheckerKind.TYPE }
    val resolvers = operation.resolverObserver as CorrectnessResolverObserver
    val checkers = operation.checkerObserver as CorrectnessCheckerObserver
    val fieldIds = checkerApplications.filter { it.checkerKind == CheckerKind.FIELD }
        .mapTo(hashSetOf()) { ResolverOccurrenceId.at(it.logicalQueryRoot, it.occurrencePath) }
    val typeIds = types.mapTo(hashSetOf()) { ResolverOccurrenceId.at(it.logicalQueryRoot, it.occurrencePath) }
    return buildSet {
        if (types.groupingBy { it.checkedType }.eachCount().any { it.value > 1 }) add(GeneratedTypeCheckerSignature.REPEATED_TYPE)
        types.forEach { checker ->
            add(GeneratedTypeCheckerSignature.TYPE_CHECKER)
            if (checker.occurrencePath.any { it is ListEngineResult.Index }) add(GeneratedTypeCheckerSignature.LIST_OCCURRENCE)
            if (checker.logicalQueryRoot !== result) add(GeneratedTypeCheckerSignature.ASSOCIATED_QUERY_OCCURRENCE)
            val ownerId = ResolverOccurrenceId.at(checker.logicalQueryRoot, checker.occurrencePath)
            if (ownerId in fieldIds) add(GeneratedTypeCheckerSignature.FIELD_AND_TYPE_SAME_PATH)
            val owner = checker.logicalQueryRoot.resultAt(checker.occurrencePath) as ObjectEngineResult
            when (owner.typeCheckerResult.get()) {
                CheckerResult.Success -> add(GeneratedTypeCheckerSignature.SUCCESS)
                is CheckerResult.Error -> add(GeneratedTypeCheckerSignature.DENIAL)
                null -> error("Invoked type checker has no result")
            }
            val definition = requireNotNull(world.resolverRegistry.typeChecker(checker.checkedType))

            fun recordCheckedInputs(
                root: ObjectEngineResult,
                path: List<PathComponent>,
                forest: SelectionForest
            ) {
                root.selectedPaths(path, forest) { selectedPath, value ->
                    val id = ResolverOccurrenceId.at(root, selectedPath)
                    if (id in fieldIds) add(GeneratedTypeCheckerSignature.RESOLVER_INPUT_FIELD_CHECK)
                    if (value is ObjectEngineResult && id in typeIds) add(GeneratedTypeCheckerSignature.RESOLVER_INPUT_TYPE_CHECK)
                }
            }

            fun recordRawInputs(
                root: ObjectEngineResult,
                path: List<PathComponent>,
                forest: SelectionForest,
                signature: GeneratedTypeCheckerSignature
            ) {
                root.selectedPaths(path, forest) { selectedPath, _ ->
                    val id = ResolverOccurrenceId.at(root, selectedPath)
                    resolvers.resolverInvocations(id).filter { !it.field.name.startsWith("__") && it.field.name != "V_A_typename" }.forEach { invocation ->
                        add(signature)
                        if (checker.occurrencePath.any { it is ListEngineResult.Index }) add(GeneratedTypeCheckerSignature.LIST_OCCURRENCE_WITH_RESOLVER_INPUT)
                        if (checker.logicalQueryRoot !== result) add(GeneratedTypeCheckerSignature.ASSOCIATED_QUERY_WITH_RESOLVER_INPUT)
                        add(
                            if (owner.typeCheckerResult.get() is CheckerResult.Error) {
                                GeneratedTypeCheckerSignature.DENIAL_WITH_RESOLVER_INPUT
                            } else {
                                GeneratedTypeCheckerSignature.SUCCESS_WITH_RESOLVER_INPUT
                            },
                        )
                        recordCheckedInputs(root, selectedPath.dropLast(1), invocation.inputSelections.constructionSelections())
                        resolvers.queryFragmentResults(id).forEach { queryRoot ->
                            recordCheckedInputs(queryRoot, emptyList(), invocation.queryInputSelections.constructionSelections())
                        }
                    }
                }
            }
            recordRawInputs(checker.logicalQueryRoot, checker.occurrencePath, definition.objectFragment, GeneratedTypeCheckerSignature.OBJECT_INPUT_RESOLVER)
            checkers.queryFragmentResults(checker.checkedTarget, ownerId).forEach { queryRoot ->
                recordRawInputs(queryRoot, emptyList(), definition.queryFragment, GeneratedTypeCheckerSignature.QUERY_INPUT_RESOLVER)
            }
        }
    }
}

private fun ObjectEngineResult.resultAt(path: List<PathComponent>): EngineResult? =
    path.fold<PathComponent, EngineResult?>(this) { value, component ->
        when (component) {
            is ObjectEngineResult.ObjectKey -> (value as ObjectEngineResult).getCell(component).value.get()
            is ListEngineResult.Index -> (value as ListEngineResult)[component.index].value.get()
        }
    }

/** Traverse only declared input demand, retaining physical keys rather than response aliases. */
private fun ObjectEngineResult.selectedPaths(
    path: List<PathComponent>,
    forest: SelectionForest,
    record: (List<PathComponent>, EngineResult?) -> Unit,
) {
    fun visit(
        value: EngineResult?,
        current: List<PathComponent>,
        selections: SelectionForest
    ) {
        when (value) {
            is ObjectEngineResult -> selections.merge(value.type).forEach { selection ->
                val key = selection.objectKey(value.type)
                if (key in value.keys) {
                    val cell = value.getCell(key)
                    if (cell.value.isCompleted) {
                        val child = cell.value.get()
                        record(current + key, child)
                        visit(child, current + key, selection.subselections)
                    }
                }
            }
            is ListEngineResult -> value.forEachIndexed { index, cell ->
                if (cell.value.isCompleted) {
                    val child = cell.value.get()
                    val childPath = current + ListEngineResult.Index.of(index)
                    record(childPath, child)
                    visit(child, childPath, selections)
                }
            }
            else -> Unit
        }
    }
    visit(resultAt(path), path, forest)
}
