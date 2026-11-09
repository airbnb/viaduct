package viaduct.tenant.runtime.execution

import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.BaseUnbatchedFieldResolver
import viaduct.api.internal.BaseUnbatchedNodeResolver
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

class UnbatchedResolverInterruptionTest {
    private val schema = MockSchema.mk("type TestNode { id: ID }")
    private val context = ContextMocks(myFullSchema = schema).engineExecutionContext

    @Test
    fun `unbatched executors propagate tenant interruption instead of returning it`(): Unit =
        runTest {
            val interruption = InterruptedException("interrupted")

            assertSame(interruption, assertThrows<InterruptedException> { fieldExecutorThrowing(interruption).batchResolve(listOf(fieldSelector()), context, null) })
            assertSame(interruption, assertThrows<InterruptedException> { nodeExecutorThrowing(interruption).resolve(listOf(nodeSelector()), context, null) })
        }

    private fun fieldExecutorThrowing(failure: Throwable): FieldUnbatchedResolverExecutorImpl {
        val resolver = object : BaseUnbatchedFieldResolver {
            override suspend fun invokeFieldResolver(context: BaseFieldExecutionContext<*, *, *>): Any? = throw failure
        }
        val factory = mockk<FieldExecutionContextFactory> { every { this@mockk.invoke(any(), any(), any(), any(), any(), any()) } answers { mockk() } }
        return FieldUnbatchedResolverExecutorImpl(null, null, false, Provider { resolver }, "Query.single", factory, "Query.single")
    }

    private fun nodeExecutorThrowing(failure: Throwable): NodeUnbatchedResolverExecutorImpl {
        val resolver = object : BaseUnbatchedNodeResolver {
            override suspend fun invokeNodeResolver(context: NodeExecutionContext<*>): Any? = throw failure
        }
        val factory = mockk<NodeExecutionContextFactory> { every { this@mockk.invoke(any(), any(), any(), any()) } answers { mockk() } }
        return NodeUnbatchedResolverExecutorImpl(Provider { resolver }, "TestNode", factory, "TestNode", false)
    }

    private fun fieldSelector() = FieldResolverExecutor.Selector(emptyMap(), null, { error("unused") }, { error("unused") })

    private fun nodeSelector() =
        NodeResolverExecutor.Selector(
            GlobalIDCodecDefault.serialize("TestNode", "a"),
            context.engineSelectionSetFactory.engineSelectionSet("TestNode", "id", emptyMap()),
        )
}
