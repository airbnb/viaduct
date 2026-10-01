package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.MaterializeSelectionForest

/** Execution capabilities available to one resolver invocation. */
interface ResolutionExecutionContext {
    /** Resolves a response-key-preserving selection set from the Query root. */
    suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync

    /** Context for semantic applications whose resolver does not use execution capabilities. */
    object Unsupported : ResolutionExecutionContext {
        override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync =
            throw UnsupportedOperationException(
                "Selection execution is not available in this resolver application",
            )
    }
}
