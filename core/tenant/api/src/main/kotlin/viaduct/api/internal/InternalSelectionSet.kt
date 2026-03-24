package viaduct.api.internal

import viaduct.engine.api.EngineSelectionSet

/**
 * A helper interface for accessing internal selection set data from a tenant-facing type
 */
interface InternalSelectionSet {
    val engineSelectionSet: EngineSelectionSet
}
