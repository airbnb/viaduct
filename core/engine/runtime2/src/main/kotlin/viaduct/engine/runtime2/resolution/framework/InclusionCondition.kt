package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.VariableBinding

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
