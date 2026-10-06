package viaduct.engine.runtime2.correctresolution

import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ObjectSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.findStoredKey
import viaduct.engine.runtime2.resolution.framework.isIncluded

/**
 * Whether this result contains every value required by [selections].
 *
 * At every object occurrence, selections are normalized against the runtime concrete object type
 * and current variable bindings before lookup. Null and error values stop recursive requirements.
 * Values not required by [selections] are permitted.
 *
 * This predicate trusts the selections' post-validation schema compatibility and the engine-result
 * carrier invariants established by its factories. It observes values, but not field or type
 * checks.
 *
 * This operation is defined only when applicable selection keys contain no unbound variables.
 */
fun ObjectEngineResult.conformsToSelections(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
): Boolean = conformsToSelectionsAt(operation, selections, emptyList())

fun ObjectEngineResult.conformsToSelections(
    operation: SharedOperationContext<*>,
    selections: ObjectSelectionForest,
): Boolean =
    type == selections.type &&
        conformsToSelectionsAt(operation, selections, emptyList())

// Checks selections rooted at an OER whose exact absolute path is supplied by the caller.
fun ObjectEngineResult.conformsToSelectionsAt(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean = objectConformsToSelections(operation, selections, path)

private fun ObjectEngineResult.objectConformsToSelections(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean =
    selections.merge(type).byKey().values.all { selection ->
        if (!selection.inclusionCondition.isIncluded(operation)) return@all true
        val key = findStoredKey(operation, selection.key)
        key != null &&
            getCell(key)
                .value
                .get()
                .engineResultConformsToSelections(
                    operation = operation,
                    selections = selection.subselections,
                    path = path + key,
                )
    }

private fun EngineResult?.engineResultConformsToSelections(
    operation: SharedOperationContext<*>,
    selections: SelectionForest,
    path: List<PathComponent>,
): Boolean =
    when (this) {
        null,
        is ErrorEngineResult,
        -> true

        is ObjectEngineResult ->
            objectConformsToSelections(
                operation = operation,
                selections = selections,
                path = path,
            )
        is ListEngineResult ->
            indices.all { index ->
                get(index).value.get().engineResultConformsToSelections(
                    operation = operation,
                    selections = selections,
                    path = path + ListEngineResult.Index.of(index),
                )
            }
        else -> true
    }
