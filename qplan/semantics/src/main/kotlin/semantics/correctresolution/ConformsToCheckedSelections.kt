package semantics.correctresolution

import model.Arguments
import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.PathComponent
import model.SelectionForest
import model.merge
import semantics.shared.SharedOperationContext
import semantics.shared.findStoredKey
import semantics.shared.groundedArguments
import semantics.shared.isIncluded

/** Whether every checked selection has a conforming field-checker result when one is registered. */
internal fun ObjectEngineResult.conformsToCheckedSelections(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
): Boolean =
    conformsToCheckedSelectionsAt(
        operation = operation,
        selections = selections,
        path = emptyList(),
        resolverApplicationCache = operation.resolverApplicationCache(this),
    )

internal fun ObjectEngineResult.conformsToCheckedSelectionsAt(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
    path: List<PathComponent>,
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    selections.merge(type).byKey().values.all { selection ->
        if (!selection.inclusionCondition.isIncluded(operation)) return@all true
        val key = findStoredKey(operation, selection.key) ?: return@all false
        val cell = getCell(key)
        val checker = operation.world.resolverRegistry.fieldChecker(key.field)
        if (checker != null) {
            if (!cell.isFieldCheckerResultSet()) return@all false
            val storedResult = cell.getFieldCheckerResult().get()
            if (
                key is ObjectEngineResult.ParentKey ||
                    key.groundedArguments(operation) !is Arguments.Resolved
            ) {
                if (storedResult != null) return@all false
            } else {
                val replayedResult =
                    reapplyChecker(
                        operation = operation,
                        resolverApplicationCache = resolverApplicationCache,
                        key = key,
                        path = path,
                    )?.result ?: return@all false
                if (!storedResult.sameResultVariantAs(replayedResult)) return@all false
            }
        }
        cell
            .getValue()
            .get()
            .engineResultConformsToCheckedSelections(
                operation = operation,
                selections = selection.subselections,
                path =
                    if (key is ObjectEngineResult.ParentKey) {
                        path.dropLast(1)
                    } else {
                        path + key
                    },
                resolverApplicationCache = resolverApplicationCache,
            )
    }

private fun EngineResult?.engineResultConformsToCheckedSelections(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
    path: List<PathComponent>,
    resolverApplicationCache: ResolverApplicationCache,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            conformsToCheckedSelectionsAt(
                operation = operation,
                selections = selections,
                path = path,
                resolverApplicationCache = resolverApplicationCache,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index)
                    .getValue()
                    .get()
                    .engineResultConformsToCheckedSelections(
                        operation = operation,
                        selections = selections,
                        path = path + ListEngineResult.Index.of(index),
                        resolverApplicationCache = resolverApplicationCache,
                    )
            }
        else -> true
    }
