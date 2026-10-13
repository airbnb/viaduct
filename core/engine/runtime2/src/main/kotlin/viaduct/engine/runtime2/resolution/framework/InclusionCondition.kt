package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MaterializeSelection
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.isUnconditional
import viaduct.engine.runtime2.model.toMaterializeSelectionForest

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
internal suspend fun MaterializeSelectionForest.fetchIncluded(operation: SharedOperationContext<*>): MaterializeSelectionForest {
    if (isUnconditional()) return this
    val selections = ArrayList<MaterializeSelection>(size)
    forEach(selections::add)
    val included = ArrayList<MaterializeSelection>(size)
    for (selection in selections) {
        if (selection.inclusionCondition.fetchIncluded(operation)) included.add(selection)
    }
    return included.toMaterializeSelectionForest()
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
