package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.usedVariables

/** Whether every variable in this key has an occurrence identity and a completed binding. */
fun ObjectEngineResult.ObjectKey.isContextuallyGrounded(operation: SharedOperationContext<*>): Boolean =
    arguments.usedVariables().all { variable ->
        variable.isInstantiated &&
            operation.variableBindings.isBound(requireNotNull(variable.instanceId))
    }

/** Grounds this key's arguments without changing the symbolic key retained by its OER cell. */
fun ObjectEngineResult.ObjectKey.groundedArguments(operation: SharedOperationContext<*>): Arguments.Ground {
    require(isContextuallyGrounded(operation)) {
        "Object key is not contextually grounded"
    }
    return arguments.instantiateBindings(operation, field)
}

/** Awaits every variable carried by this key before grounding its arguments. */
suspend fun ObjectEngineResult.ObjectKey.fetchGroundedArguments(operation: SharedOperationContext<*>): Arguments.Ground {
    arguments.usedVariables().forEach { variable ->
        require(variable.isInstantiated) {
            "Variable template $variable must be instantiated before its binding can be fetched"
        }
        operation.variableBindings.fetchBinding(requireNotNull(variable.instanceId))
    }
    return groundedArguments(operation)
}
