package semantics.correctresolution

import model.EngineResult
import model.ErrorEngineResult
import model.ListEngineResult
import model.ObjectEngineResult
import model.ObjectSelectionForest
import model.PathComponent
import model.SelectionForest
import model.merge
import semantics.shared.findStoredKey
import semantics.shared.isIncluded
import semantics.shared.SharedOperationContext

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
