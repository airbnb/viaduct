package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.isUnconditional
import viaduct.engine.runtime2.model.requireResponseKey
import viaduct.engine.runtime2.model.toSelectionForest

/** Awaits the operation-local variable bindings needed to decide this condition. */
internal suspend fun InclusionCondition.fetchIncluded(operation: SharedOperationContext<*>): Boolean =
    include { variable ->
        when (
            val binding =
                operation.variableBindings.fetchBinding(
                    requireNotNull(variable.instanceId),
                )
        ) {
            VariableBinding.Error -> error("Inclusion-condition variable failed")
            is VariableBinding.Input ->
                binding.value as? Boolean
                    ?: error("Inclusion-condition variable must contain a Boolean")
        }
    }

/** Preserves unconditional forests and accumulates conditional occurrences without prefix copies. */
internal suspend fun SelectionForest.fetchIncluded(operation: SharedOperationContext<*>): SelectionForest {
    if (isUnconditional()) return this
    val selections = ArrayList<Selection>(size)
    forEach { selection ->
        // Reject construction demand before an excluded or unresolved guard can hide the misuse.
        selection.requireResponseKey()
        selections.add(selection)
    }
    val included = ArrayList<Selection>(size)
    for (selection in selections) {
        if (selection.inclusionCondition.fetchIncluded(operation)) included.add(selection)
    }
    return included.toSelectionForest()
}

/** Evaluates this condition from bindings that must already be complete. */
internal fun InclusionCondition.isIncluded(operation: SharedOperationContext<*>): Boolean =
    includeWith { variable ->
        when (
            val binding =
                operation.variableBindings.getBinding(
                    requireNotNull(variable.instanceId),
                )
        ) {
            VariableBinding.Error -> error("Inclusion-condition variable failed")
            is VariableBinding.Input ->
                binding.value as? Boolean
                    ?: error("Inclusion-condition variable must contain a Boolean")
        }
    }
