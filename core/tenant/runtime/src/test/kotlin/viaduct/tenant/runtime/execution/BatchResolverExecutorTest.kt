@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.api.internal.BaseBatchedNodeResolver
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.FrameworkException
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

@Suppress("UNUSED_PARAMETER")
class BatchResolverExecutorTest {
    interface TestNodeObject : NodeObject

    class NonListFieldBatchResolver : BaseBatchedFieldResolver {
        override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any = "not a list"
    }

    class NonListNodeBatchResolver : BaseBatchedNodeResolver {
        override suspend fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): Any = "not a list"
    }

    @Test
    fun `field batch executor throws FrameworkException for non-list batchResolve result`() {
        val resolver = NonListFieldBatchResolver()
        val resolverContextFactory = mockk<FieldExecutionContextFactory>()
        every {
            resolverContextFactory.invoke(any(), any(), any(), any(), any(), any())
        } returns mockk(relaxed = true)

        val executor = FieldBatchResolverExecutorImpl(
            objectSelectionSet = null,
            querySelectionSet = null,
            isSelective = false,
            resolver = Provider { resolver },
            resolverId = "Query.testField",
            resolverContextFactory = resolverContextFactory,
            resolverName = "Query.testField",
        )

        val exception = assertThrows<FrameworkException> {
            runBlocking {
                executor.batchResolve(
                    selectors = listOf(createFieldSelector()),
                    context = createExecutionContext()
                )
            }
        }

        assertEquals(
            "Unexpected return value from batchResolve function for field Query.testField: not a list",
            exception.message
        )
    }

    @Test
    fun `node batch executor throws FrameworkException for non-list batchResolve result`() {
        val resolver = NonListNodeBatchResolver()
        val resolverContextFactory = mockk<NodeExecutionContextFactory>()
        every {
            resolverContextFactory.invoke(any(), any(), any(), any())
        } returns mockk(relaxed = true)

        val executor = NodeBatchResolverExecutorImpl(
            resolver = Provider { resolver },
            typeName = "TestNode",
            factory = resolverContextFactory,
            resolverName = "TestNode",
            isSelective = false,
        )

        val exception = assertThrows<FrameworkException> {
            runBlocking {
                executor.resolve(
                    selectors = listOf(
                        NodeResolverExecutor.Selector(
                            "gid://test/1",
                            mockk<EngineSelectionSet>(relaxed = true)
                        )
                    ),
                    context = createExecutionContext()
                )
            }
        }

        assertEquals(
            "Unexpected return value from batchResolve function for node TestNode: not a list",
            exception.message
        )
    }

    private fun createFieldSelector(): FieldResolverExecutor.Selector =
        FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            selections = null,
            syncObjectValueGetter = { mockk(relaxed = true) },
            syncQueryValueGetter = { mockk(relaxed = true) },
        )

    private fun createExecutionContext(): EngineExecutionContext =
        mockk {
            every { requestContext } returns null
        }
}
