package viaduct.engine.runtime2.model.registry

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.SelectionForest

data class InteropAncestor(
    val path: List<PathComponent>,
    val arguments: Arguments.Resolved,
    val demand: SelectionForest,
    val additionalContextForResolver: Any? = null,
)

/** Execution capabilities available to one resolver invocation. */
interface ResolutionExecutionContext {
    /** Reconstructs integration metadata without retaining GraphQL execution steps. */
    suspend fun interopAncestors(): List<InteropAncestor> = emptyList()

    /** Request-owned Engine API context, when execution was entered through an engine adapter. */
    val engineExecutionContext: EngineExecutionContext?
        get() = null

    /** Resolves a response-key-preserving selection set from the Query root. */
    suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync

    /** Resolves an ordered Mutation selection and returns its response-keyed projection. */
    suspend fun resolveMutationSelectionSet(
        selections: MaterializeSelectionForest,
        mutations: MutationSelectionForest,
    ): EngineObjectData.Sync =
        throw UnsupportedOperationException(
            "Mutation selection execution is not available in this resolver application",
        )

    /** Context for semantic applications whose resolver does not use execution capabilities. */
    object Unsupported : ResolutionExecutionContext {
        override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync =
            throw UnsupportedOperationException(
                "Selection execution is not available in this resolver application",
            )
    }
}
