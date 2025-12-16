package viaduct.api.internal

import viaduct.engine.api.RawSelectionSet

/**
 * A helper interface for accessing internal selection set data from a tenant-facing type
 */
interface InternalSelectionSet {
    val rawSelectionSet: RawSelectionSet
}
