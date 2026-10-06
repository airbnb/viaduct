package viaduct.engine.runtime2.resolution

import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class EngineExecutionContextPropagationTest {
    @Test
    fun `child resolution scopes retain the request engine context`() =
        runTest {
            val world =
                TestWorld.fromSDL(
                    """
                    type Query {
                      value: Int
                    }
                    """.trimIndent(),
                )
            val engineContext = engineExecutionContextStub()
            val shared =
                SharedOperationContext.create(
                    world = world.assumptions,
                    engineExecutionContext = engineContext,
                )

            assertSame(engineContext, shared.engineExecutionContext)
            val operation = OperationContext.create(shared, this)
            assertSame(engineContext, operation.engineExecutionContext)
            assertSame(engineContext, operation.forChildScope(this).engineExecutionContext)
        }

    private fun engineExecutionContextStub(): EngineExecutionContext =
        Proxy.newProxyInstance(
            EngineExecutionContext::class.java.classLoader,
            arrayOf(EngineExecutionContext::class.java),
        ) { _, method, _ ->
            error("Unexpected EngineExecutionContext.${method.name} invocation")
        } as EngineExecutionContext
}
