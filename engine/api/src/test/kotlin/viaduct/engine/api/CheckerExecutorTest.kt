package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CheckerExecutorTest {
    private class TestCheckerExecutor : CheckerExecutor {
        override suspend fun execute(
            arguments: Map<String, Any?>,
            objectDataMap: Map<String, EngineObjectData>,
            context: EngineExecutionContext,
            checkerType: CheckerExecutor.CheckerType
        ): CheckerResult = CheckerResult.Success
    }

    @Test
    fun testRequiredSelectionSet() {
        val ce = CheckerExecutorTest.TestCheckerExecutor()
        assertEquals(emptyMap<String, RequiredSelectionSet?>(), ce.requiredSelectionSets)
    }
}
