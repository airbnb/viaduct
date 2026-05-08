@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.NodeResolverBase
import viaduct.api.ResolverBase
import viaduct.api.internal.ReflectionLoader
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.FrameworkException
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

@Suppress("UNUSED_PARAMETER")
class BatchResolverExecutorTest {
    interface TestNodeObject : NodeObject

    class NonListFieldBatchResolver : ResolverBase<String> {
        @Suppress("unused")
        suspend fun batchResolve(contexts: List<Any?>): Any = "not a list"
    }

    class NonListNodeBatchResolver : NodeResolverBase<TestNodeObject> {
        @Suppress("unused")
        suspend fun batchResolve(contexts: List<Any?>): Any = "not a list"
    }

    @Test
    fun `field batch executor throws FrameworkException for non-list batchResolve result`() {
        val resolver = NonListFieldBatchResolver()
        val resolverContextFactory = mockk<FieldExecutionContextFactory>()
        every {
            resolverContextFactory.invoke(any(), any(), any(), any(), any(), any(), any(), any())
        } returns mockk(relaxed = true)

        val executor = FieldBatchResolverExecutorImpl(
            objectSelectionSet = null,
            querySelectionSet = null,
            isSelective = false,
            resolver = Provider { resolver },
            batchResolveFn = NonListFieldBatchResolver::batchResolve,
            resolverId = "Query.testField",
            reflectionLoader = mockk<ReflectionLoader>(relaxed = true),
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

        kotlin.test.assertEquals(
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
            batchResolveFunction = NonListNodeBatchResolver::batchResolve,
            typeName = "TestNode",
            reflectionLoader = mockk<ReflectionLoader>(relaxed = true),
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

        kotlin.test.assertEquals(
            "Unexpected return value from batchResolve function for node TestNode: not a list",
            exception.message
        )
    }

    private fun createFieldSelector(): FieldResolverExecutor.Selector =
        FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            objectValue = mockk<EngineObjectData>(relaxed = true),
            queryValue = mockk<EngineObjectData>(relaxed = true),
            selections = null,
        )

    private fun createExecutionContext(): EngineExecutionContext =
        mockk {
            every { requestContext } returns null
        }
}
