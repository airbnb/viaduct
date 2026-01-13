package viaduct.engine.runtime

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet

/**
 * An EngineObjectData that is not fully resolved until [resolveData] is called.
 */
interface LazyEngineObjectData : EngineObjectData {
    /**
     * Resolves the data for this lazy object.
     *
     * @return true if the data was resolved by this call, false if it was already called previously
     */
    suspend fun resolveData(
        selections: RawSelectionSet,
        context: EngineExecutionContext
    ): Boolean
}
