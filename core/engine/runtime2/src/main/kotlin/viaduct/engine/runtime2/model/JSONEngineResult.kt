package viaduct.engine.runtime2.model

/** A JSON scalar leaf with structural equality over its copied scalar value. */
sealed interface JSONEngineResult {
    val value: EngineOutputData

    companion object {
        /** Copies JSON containers while retaining opaque JVM values. */
        fun of(value: EngineOutputData): JSONEngineResult = JSONEngineResultImpl(requireNotNull(value.copyJsonValue()))
    }
}

private data class JSONEngineResultImpl(
    override val value: EngineOutputData,
) : JSONEngineResult
