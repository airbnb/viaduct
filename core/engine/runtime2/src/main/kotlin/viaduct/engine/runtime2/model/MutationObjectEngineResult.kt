package viaduct.engine.runtime2.model

/** A mutation namespace occurrence whose publications retain response-key identity. */
sealed interface MutationObjectEngineResult : ObjectEngineResult {
    override val keys: Set<ObjectEngineResult.MutationKey>

    companion object {
        fun of(selections: MutationSelectionForest): MutationObjectEngineResult = mutationObjectEngineResultOf(selections)
    }
}
