package viaduct.engine.api

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
