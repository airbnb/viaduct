package viaduct.engine.api

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.apiannotations.InternalApi

@OptIn(InternalApi::class)
class BatchExecutionContextTest {
    @Test
    fun `invocationContextFor returns the receiver when it carries no invocation contexts`() {
        val plain = mockk<EngineExecutionContext>()

        assertSame(plain, plain.invocationContextFor("anything"))
    }

    @Test
    fun `invocationContextFor preserves decorators outside a batch`() {
        val decorated = object : EngineExecutionContext by mockk<EngineExecutionContext>() {}

        assertSame(decorated, decorated.invocationContextFor("anything"))
    }

    @Test
    fun `invocationContextFor returns the context selected by the batch`() {
        val first = mockk<EngineExecutionContext>()
        val second = mockk<EngineExecutionContext>()
        val invocations = mapOf<Any, EngineExecutionContext>("a" to first, "b" to second)
        val context: EngineExecutionContext = object : BatchExecutionContext, EngineExecutionContext by mockk<EngineExecutionContext>() {
            override fun invocationContextFor(selector: Any): EngineExecutionContext = invocations.getValue(selector)
        }

        assertSame(first, context.invocationContextFor("a"))
        assertSame(second, context.invocationContextFor("b"))
    }

    @Test
    fun `invocationContextFor propagates a missing selector failure`() {
        val failure = IllegalStateException("Selector was never captured")
        val context: EngineExecutionContext = object : BatchExecutionContext, EngineExecutionContext by mockk<EngineExecutionContext>() {
            override fun invocationContextFor(selector: Any): EngineExecutionContext = throw failure
        }

        assertSame(failure, assertThrows<IllegalStateException> { context.invocationContextFor("missing") })
    }
}
