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

/** Validates field checks and the type checks of objects reached through checked selections. */
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
    typeCheckDemanded: Boolean = false,
): Boolean =
    (!typeCheckDemanded || conformsToTypeChecker(operation, resolverApplicationCache, path)) &&
        selections.merge(type).byKey().values.all { selection ->
            if (!selection.inclusionCondition.isIncluded(operation)) return@all true
            val key = findStoredKey(operation, selection.key) ?: return@all false
            val cell = getCell(key)
            val checker = operation.world.resolverRegistry.fieldChecker(key.field)
            if (checker != null) {
                val storedResult = cell.fieldCheckerResult.get()
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
            val value = cell.value.get()
            value.engineResultConformsToCheckedSelections(
                operation = operation,
                selections = selection.subselections,
                path =
                    if (key is ObjectEngineResult.ParentKey && value is ObjectEngineResult) {
                        resolverApplicationCache.objectPath(value) ?: return@all false
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
                typeCheckDemanded = true,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index)
                    .value
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

/** A raw root is not checked merely because a resolver inside it has checked fixed inputs. */
private fun ObjectEngineResult.conformsToTypeChecker(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
    path: List<PathComponent>,
): Boolean {
    val stored = typeCheckerResult.get()
    if (operation.world.resolverRegistry.typeChecker(type) == null) return stored == null
    if (stored == null) return false
    val replayed = reapplyTypeChecker(operation, resolverApplicationCache, path)?.result ?: return false
    return stored.sameResultVariantAs(replayed)
}
