package viaduct.engine.api

import graphql.ExecutionResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class EngineTest {
    @Test
    fun `engines conservatively default to complete delivery`() {
        val engine = object : Engine {
            override val schema: EngineSchema get() = error("unused")

            override suspend fun execute(
                executionInput: ExecutionInput,
                incrementalExecution: Boolean,
            ): ExecutionResult = error("unused")

            override suspend fun resolveSelectionSet(
                executionHandle: EngineExecutionContext.ExecutionHandle,
                selectionSet: EngineSelectionSet,
                options: ResolveSelectionSetOptions,
            ): EngineObjectData.Sync = error("unused")

            override suspend fun resolveRootFieldReference(
                executionHandle: EngineExecutionContext.ExecutionHandle,
                rootFieldPath: List<String>,
                arguments: Map<String, Any?>,
                selectionSet: EngineSelectionSet,
                options: ResolveRootFieldReferenceOptions,
            ): EngineObjectData? = error("unused")
        }

        assertFalse(engine.supportsIncrementalExecution)
    }
}
