package viaduct.engine.runtime

import com.fasterxml.jackson.annotation.JsonValue
import kotlinx.coroutines.CompletableDeferred

class DeferredLateResolvedVariable(
    val variableName: String
) : CompletableDeferred<Any?> by CompletableDeferred(), LateResolvedVariable {
    override suspend fun resolve(): Any? {
        return this.await()
    }

    @JsonValue
    override fun toString(): String {
        return "DFPLateResolvedVariable(variable: $variableName)"
    }
}
