@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime.execution

import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.api.internal.BaseBatchedNodeResolver
import viaduct.api.internal.ObjectBase
import viaduct.api.types.NodeObject
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.withInvocationContexts
import viaduct.errors.FrameworkException
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.context.factory.FieldExecutionContextFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory

@Suppress("UNUSED_PARAMETER")
class BatchResolverExecutorTest {
    class TestNodeGRT(data: EngineObject) : ObjectBase(mockk(relaxed = true), data), NodeObject

    class NonListFieldBatchResolver : BaseBatchedFieldResolver {
        override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any = "not a list"
    }

    class ConfigurableNodeBatchResolver :
        BaseBatchedNodeResolver {
        val invocations = mutableListOf<List<NodeExecutionContext<*>>>()
        var resultFactory:
            suspend (List<NodeExecutionContext<*>>) -> Map<NodeExecutionContext<*>, FieldValue<*>> =
            { emptyMap() }

        override suspend fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): Map<NodeExecutionContext<*>, FieldValue<*>> {
            invocations.add(contexts)
            return resultFactory(contexts)
        }
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
    fun `node batch executor binds results by context independent of map order`() {
        val contexts = listOf(mockk<NodeExecutionContext<*>>(), mockk<NodeExecutionContext<*>>())
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = {
                linkedMapOf(
                    contexts[1] to FieldValue.ofError(RuntimeException("second")),
                    contexts[0] to FieldValue.ofError(RuntimeException("first")),
                )
            }
        }
        val selectors = listOf(createNodeSelector("1"), createNodeSelector("2"))

        val result = runBlocking {
            createNodeExecutor(resolver, contexts).resolve(selectors, createExecutionContext())
        }

        assertEquals("first", deepestMessage(result.getValue(selectors[0])))
        assertEquals("second", deepestMessage(result.getValue(selectors[1])))
    }

    @Test
    fun `node batch executor rejects omitted context key`() {
        val context = mockk<NodeExecutionContext<*>>()
        val resolver = ConfigurableNodeBatchResolver()
        val selector = createNodeSelector("missing")

        val result = runBlocking {
            createNodeExecutor(resolver, listOf(context)).resolve(listOf(selector), createExecutionContext())
        }

        assertTrue(result.getValue(selector).exceptionOrNull() is TenantResolverException)
    }

    @Test
    fun `node batch executor rejects foreign context key`() {
        val inputContext = mockk<NodeExecutionContext<*>>()
        val foreignContext = mockk<NodeExecutionContext<*>>()
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = {
                mapOf(foreignContext to FieldValue.ofError(RuntimeException("error")))
            }
        }
        val selector = createNodeSelector("1")

        val result = runBlocking {
            createNodeExecutor(resolver, listOf(inputContext))
                .resolve(listOf(selector), createExecutionContext())
        }

        assertTrue(result.getValue(selector).exceptionOrNull() is TenantResolverException)
    }

    @Test
    fun `node batch executor runs duplicate internal id groups concurrently`() {
        val contexts = listOf(mockk<NodeExecutionContext<*>>(), mockk<NodeExecutionContext<*>>())
        val allInvocationsStarted = CompletableDeferred<Unit>()
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = { invocationContexts ->
                if (invocations.size == contexts.size) {
                    allInvocationsStarted.complete(Unit)
                }
                withTimeout(5_000) {
                    allInvocationsStarted.await()
                }
                invocationContexts.associateWith {
                    FieldValue.ofError(RuntimeException("expected"))
                }
            }
        }
        val selectors = listOf(createNodeSelector("same"), createNodeSelector("same"))

        val result = runBlocking {
            createNodeExecutor(resolver, contexts).resolve(selectors, createExecutionContext())
        }

        assertEquals(listOf(1, 1), resolver.invocations.map { it.size })
        assertEquals(2, result.size)
    }

    @Test
    fun `node batch executor rejects context returned from another split invocation`() {
        val contexts = listOf(mockk<NodeExecutionContext<*>>(), mockk<NodeExecutionContext<*>>())
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = { invocationContexts ->
                val returnedContext =
                    if (invocationContexts.single() === contexts.first()) contexts.last() else contexts.first()
                mapOf(returnedContext to FieldValue.ofError(RuntimeException("expected")))
            }
        }
        val selectors = listOf(createNodeSelector("same"), createNodeSelector("same"))

        val result = runBlocking {
            createNodeExecutor(resolver, contexts).resolve(selectors, createExecutionContext())
        }

        assertTrue(result.values.all { it.exceptionOrNull() is TenantResolverException })
    }

    @Test
    fun `node batch executor isolates a failing split invocation`() {
        val contexts = listOf(mockk<NodeExecutionContext<*>>(), mockk<NodeExecutionContext<*>>())
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = { invocationContexts ->
                if (invocationContexts.single() === contexts.last()) {
                    throw RuntimeException("second invocation failed")
                }
                invocationContexts.associateWith { FieldValue.ofError(RuntimeException("expected")) }
            }
        }
        val selectors = listOf(createNodeSelector("same"), createNodeSelector("same"))

        val result = runBlocking {
            createNodeExecutor(resolver, contexts).resolve(selectors, createExecutionContext())
        }

        assertEquals("expected", deepestMessage(result.getValue(selectors.first())))
        assertEquals("second invocation failed", deepestMessage(result.getValue(selectors.last())))
    }

    @Test
    fun `node batch executor builds each tenant context from its selector invocation context`() {
        val firstSelector = createNodeSelector("1")
        val secondSelector = createNodeSelector("2")
        val firstContext = createExecutionContext()
        val secondContext = createExecutionContext()
        val batchContext =
            createExecutionContext().withInvocationContexts(
                mapOf(
                    firstSelector to firstContext,
                    secondSelector to secondContext,
                )
            )
        val capturedContexts = mutableListOf<EngineExecutionContext>()
        val resolverContextFactory = mockk<NodeExecutionContextFactory>()
        every {
            resolverContextFactory.invoke(capture(capturedContexts), any(), any(), any())
        } returnsMany listOf(mockk(relaxed = true), mockk(relaxed = true))
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = { contexts ->
                contexts.associateWith { FieldValue.ofError(RuntimeException("expected")) }
            }
        }
        val executor = NodeBatchResolverExecutorImpl(
            resolver = Provider { resolver },
            typeName = "TestNode",
            factory = resolverContextFactory,
            resolverName = "TestNode",
            isSelective = false,
        )

        runBlocking {
            executor.resolve(listOf(firstSelector, secondSelector), batchContext)
        }

        assertEquals(listOf(firstContext, secondContext), capturedContexts)
    }

    @Test
    fun `node batch executor keeps success and error values on their selectors`() {
        val contexts = listOf(mockk<NodeExecutionContext<*>>(), mockk<NodeExecutionContext<*>>())
        val data = mockk<EngineObjectData.Sync>()
        val failure = IllegalStateException("second failed")
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = {
                linkedMapOf(
                    contexts[1] to FieldValue.ofError(failure),
                    contexts[0] to FieldValue.ofValue(TestNodeGRT(data)),
                )
            }
        }
        val selectors = listOf(createNodeSelector("1"), createNodeSelector("2"))

        val result = runBlocking {
            createNodeExecutor(resolver, contexts).resolve(selectors, createExecutionContext())
        }

        assertSame(data, result.getValue(selectors[0]).getOrThrow())
        val error = assertInstanceOf(TenantResolverException::class.java, result.getValue(selectors[1]).exceptionOrNull())
        assertSame(failure, error.cause)
    }

    @Test
    fun `node batch executor attributes null invalid and unresolved reference results`() {
        val contexts = List(3) { mockk<NodeExecutionContext<*>>() }
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = {
                mapOf(
                    contexts[0] to FieldValue.ofValue(null),
                    contexts[1] to FieldValue.ofValue("not a GRT"),
                    contexts[2] to FieldValue.ofValue(TestNodeGRT(mockk<NodeReference>())),
                )
            }
        }
        val selectors = listOf(createNodeSelector("1"), createNodeSelector("2"), createNodeSelector("3"))

        val result = runBlocking {
            createNodeExecutor(resolver, contexts).resolve(selectors, createExecutionContext())
        }

        selectors.forEach { selected ->
            val error = assertInstanceOf(TenantResolverException::class.java, result.getValue(selected).exceptionOrNull())
            assertInstanceOf(TenantUsageException::class.java, error.cause)
        }
    }

    @Test
    fun `node batch executor attributes independent resolver cancellation`() {
        val cancellation = CancellationException("resolver cancelled")
        val resolver = ConfigurableNodeBatchResolver().apply {
            resultFactory = { throw cancellation }
        }
        val selector = createNodeSelector("1")

        val result = runBlocking {
            createNodeExecutor(resolver, listOf(mockk())).resolve(listOf(selector), createExecutionContext())
        }

        val error = assertInstanceOf(TenantResolverException::class.java, result.getValue(selector).exceptionOrNull())
        assertSame(cancellation, error.cause)
    }

    @Test
    fun `request cancellation stops a pending node batch invocation`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val pending = CompletableDeferred<Unit>()
            val resolver = ConfigurableNodeBatchResolver().apply {
                resultFactory = {
                    started.complete(Unit)
                    try {
                        pending.await()
                    } finally {
                        stopped.complete(Unit)
                    }
                    emptyMap()
                }
            }
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                createNodeExecutor(resolver, listOf(mockk())).resolve(listOf(createNodeSelector("1")), createExecutionContext())
            }
            started.await()
            request.cancel()

            assertThrows<CancellationException> { request.await() }
            withTimeout(5_000) { stopped.await() }
        }

    private fun createFieldSelector(): FieldResolverExecutor.Selector =
        FieldResolverExecutor.Selector(
            arguments = emptyMap(),
            selections = null,
            syncObjectValueGetter = { mockk(relaxed = true) },
            syncQueryValueGetter = { mockk(relaxed = true) },
        )

    private fun createNodeSelector(internalID: String): NodeResolverExecutor.Selector =
        NodeResolverExecutor.Selector(
            GlobalIDCodecDefault.serialize("TestNode", internalID),
            mockk<EngineSelectionSet>(),
        )

    private fun createNodeExecutor(
        resolver: ConfigurableNodeBatchResolver,
        contexts: List<NodeExecutionContext<*>>,
    ): NodeBatchResolverExecutorImpl {
        val resolverContextFactory = mockk<NodeExecutionContextFactory>()
        every {
            resolverContextFactory.invoke(any(), any(), any(), any())
        } returnsMany contexts
        return NodeBatchResolverExecutorImpl(
            resolver = Provider { resolver },
            typeName = "TestNode",
            factory = resolverContextFactory,
            resolverName = "TestNode",
            isSelective = true,
        )
    }

    private fun deepestMessage(result: Result<*>): String? = generateSequence(result.exceptionOrNull()) { it.cause }.last().message

    private fun createExecutionContext(): EngineExecutionContext = ContextMocks().engineExecutionContext
}
