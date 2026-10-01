package viaduct.engine.runtime2.resolution.framework

import viaduct.engine.runtime2.model.ObjectEngineResult

/**
 * Finds the cell addressed by one contextually grounded selection key.
 *
 * Symbolic identity wins when the result retains it. Older resolver families may instead store
 * the key's grounded projection.
 */
internal fun ObjectEngineResult.findStoredKey(
    operation: SharedOperationContext<*>,
    candidate: ObjectEngineResult.ObjectKey,
): ObjectEngineResult.ObjectKey? {
    if (!candidate.isContextuallyGrounded(operation)) return null
    if (candidate in keys) return candidate
    val arguments = candidate.groundedArguments(operation)
    val grounded = ObjectEngineResult.GroundKey.of(candidate.field, arguments)
    return grounded.takeIf { it in keys }
}
