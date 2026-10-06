package viaduct.engine.runtime2.model

/** An opaque scalar leaf; equality compares the retained payload by reference identity. */
sealed interface BackingDataEngineResult {
    val value: EngineOutputData

    companion object {
        fun of(value: EngineOutputData): BackingDataEngineResult = BackingDataEngineResultImpl(value)
    }
}

private class BackingDataEngineResultImpl(
    override val value: EngineOutputData,
) : BackingDataEngineResult {
    override fun equals(other: Any?): Boolean = other is BackingDataEngineResult && value === other.value

    override fun hashCode(): Int = System.identityHashCode(value)
}
