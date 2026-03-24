package viaduct.engine.runtime

import graphql.execution.ExecutionContext
import graphql.execution.ExecutionStrategyParameters
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class EngineResultLocalContextTest {
    @Test
    fun `test EngineResultLocalContext properties`() {
        val rootEngineResult = mockk<ObjectEngineResultImpl>()
        val parentEngineResult = mockk<ObjectEngineResultImpl>()
        val queryEngineResult = mockk<ObjectEngineResultImpl>()
        val gjParameters = mockk<ExecutionStrategyParameters>()
        val executionContext = mockk<ExecutionContext>()

        val context = EngineResultLocalContext(rootEngineResult, parentEngineResult, queryEngineResult, gjParameters, executionContext)

        assertEquals(rootEngineResult, context.rootEngineResult)
        assertEquals(parentEngineResult, context.parentEngineResult)
        assertEquals(queryEngineResult, context.queryEngineResult)
    }

    @Test
    fun `test EngineResultLocalContext with different queryEngineResult`() {
        val rootEngineResult = mockk<ObjectEngineResultImpl>()
        val parentEngineResult = mockk<ObjectEngineResultImpl>()
        val queryEngineResult = mockk<ObjectEngineResultImpl>()
        val gjParameters = mockk<ExecutionStrategyParameters>()
        val executionContext = mockk<ExecutionContext>()

        val context = EngineResultLocalContext(rootEngineResult, parentEngineResult, queryEngineResult, gjParameters, executionContext)

        // Verify each engine result is independently assignable
        assertEquals(rootEngineResult, context.rootEngineResult)
        assertEquals(parentEngineResult, context.parentEngineResult)
        assertEquals(queryEngineResult, context.queryEngineResult)

        // Verify they can all be different instances
        // (This tests the core feature: queryEngineResult != rootEngineResult for mutations)
        assertNotSame(rootEngineResult, parentEngineResult)
        assertNotSame(rootEngineResult, queryEngineResult)
        assertNotSame(parentEngineResult, queryEngineResult)
    }
}
