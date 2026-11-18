package viaduct.engine.runtime2.model

import viaduct.engine.api.CheckerResult

/** A mutation namespace occurrence whose publications retain response-key identity. */
sealed interface MutationObjectEngineResult : ObjectEngineResult {
    override val keys: Set<ObjectEngineResult.MutationKey>

    companion object {
        fun of(
            selections: MutationSelectionForest,
            typeCheckerResult: Promise<CheckerResult?> = Promise.of(null),
        ): MutationObjectEngineResult = mutationObjectEngineResultOf(selections, typeCheckerResult)
    }
}
