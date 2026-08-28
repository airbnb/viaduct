package viaduct.engine.api.spi

import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData

sealed interface VariableDefinitions {
    val variableNames: Set<String>
}

data class VariableFromArgumentDefinitions(
    val variables: Map<String, String>,
) : VariableDefinitions {
    override val variableNames: Set<String> = variables.keys

    companion object {
        @JvmStatic
        val EMPTY = VariableFromArgumentDefinitions(emptyMap())
    }
}

data class VariableFromFieldDefinitions(
    val variables: Map<String, String>,
) : VariableDefinitions {
    override val variableNames: Set<String> = variables.keys

    companion object {
        @JvmStatic
        val EMPTY = VariableFromFieldDefinitions(emptyMap())
    }
}

interface VariableFromFunctionDefinitions : VariableDefinitions {
    suspend fun provideVariables(
        objectData: EngineObjectData.Sync,
        arguments: Map<String, Any?>,
        context: EngineExecutionContext,
    ): Map<String, Any?>
}
