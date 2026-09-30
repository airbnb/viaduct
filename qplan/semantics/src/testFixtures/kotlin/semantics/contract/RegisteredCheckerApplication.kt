@file:Suppress("ForbiddenImport")

package semantics.contract

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.registry.ResolverTarget
import semantics.correctresolution.CorrectnessCheckerObserver
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.CheckerInvocationObservation
import semantics.shared.CheckerKind
import semantics.shared.SharedOperationContext
import semantics.shared.groundedArguments

/**
 * Reconstructs the published field- and type-checker ledger from completed slots independently of
 * invocation observation. This checks missing/duplicate observations, not whether a slot was needed.
 * Pair it with correctResolution and demandedTypeCheckerApplications for independent input/value
 * validation and checked type-demand exactness.
 */
internal fun EngineResult?.registeredCheckerApplications(operation: SharedOperationContext<*>): List<CheckerInvocationObservation> {
    val primaryRoot = this as? ObjectEngineResult ?: return emptyList()
    val roots =
        buildList {
            add(primaryRoot)
            addAll(
                (operation.resolverObserver as CorrectnessResolverObserver)
                    .allQueryFragmentResults()
                    .values
                    .flatten(),
            )
            addAll(
                (operation.checkerObserver as CorrectnessCheckerObserver)
                    .allQueryFragmentResults()
                    .values
                    .flatten(),
            )
        }
    val visitedRoots =
        Collections.newSetFromMap(IdentityHashMap<ObjectEngineResult, Boolean>())
    return buildList {
        roots.forEach { root ->
            if (!visitedRoots.add(root)) return@forEach
            root.forEachPublishedCheckerApplication(operation, ::add)
        }
    }
}

private fun ObjectEngineResult.forEachPublishedCheckerApplication(
    operation: SharedOperationContext<*>,
    record: (CheckerInvocationObservation) -> Unit,
) {
    val logicalQueryRoot = this

    fun visit(
        result: EngineResult?,
        path: List<PathComponent>,
    ) {
        if (result == null || result is ErrorEngineResult) return
        when (result) {
            is ObjectEngineResult -> {
                if (result.typeCheckerResult.get() != null) {
                    checkNotNull(operation.world.resolverRegistry.typeChecker(result.type))
                    record(
                        CheckerInvocationObservation(
                            checkerKind = CheckerKind.TYPE,
                            logicalQueryRoot = logicalQueryRoot,
                            occurrencePath = path,
                            arguments = null,
                            checkedTarget = ResolverTarget.TypeCheckerTarget(result.type),
                        ),
                    )
                }
                result.keys.forEach { key ->
                    val cell = result.getCell(key)
                    if (!runBlocking { cell.fetchActivated() }) return@forEach
                    val occurrencePath = path + key
                    val checker = operation.world.resolverRegistry.fieldChecker(key.field)
                    val checkerResult = cell.fieldCheckerResult.get()
                    if (checkerResult != null) {
                        checkNotNull(checker) {
                            "Unregistered field checker published a result: $occurrencePath"
                        }
                        val arguments = key.groundedArguments(operation) as? Arguments.Resolved
                        checkNotNull(arguments) {
                            "Published checker occurrence is not grounded: $occurrencePath"
                        }
                        record(
                            CheckerInvocationObservation(
                                checkerKind = CheckerKind.FIELD,
                                logicalQueryRoot = logicalQueryRoot,
                                occurrencePath = occurrencePath,
                                arguments = arguments,
                                checkedTarget = ResolverTarget.FieldCheckerTarget(key.field),
                            ),
                        )
                    }
                    if (key !is ObjectEngineResult.ParentKey) {
                        visit(cell.value.get(), occurrencePath)
                    }
                }
            }

            is ListEngineResult ->
                result.forEachIndexed { index, cell ->
                    if (runBlocking { cell.fetchActivated() }) {
                        visit(
                            cell.value.get(),
                            path + ListEngineResult.Index.of(index),
                        )
                    }
                }

            else -> Unit
        }
    }

    visit(this, emptyList())
}
