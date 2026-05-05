package viaduct.api.internal

import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineSelectionSet

/**
 * A helper interface for accessing internal selection set data from a tenant-facing type
 */
@InternalApi
interface InternalSelectionSet {
    val engineSelectionSet: EngineSelectionSet
}
