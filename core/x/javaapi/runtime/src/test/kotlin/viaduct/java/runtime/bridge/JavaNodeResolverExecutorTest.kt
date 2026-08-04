@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.java.api.context.NodeExecutionContext
import viaduct.java.api.internal.BaseBatchedNodeResolver
import viaduct.java.api.internal.BaseUnbatchedNodeResolver
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.resolvers.FieldValue
import viaduct.java.api.types.NodeObject
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

private class TestNodeGRT : ObjectBase, NodeObject {
    constructor(data: EngineObjectData.Sync) : super(null, data)
    constructor(ref: NodeReference) : super(null, ref)
}

class JavaNodeResolverExecutorTest {
    private fun mockEngineContext(): EngineExecutionContext =
        mockk {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
        }

    private fun selector(id: String = GlobalIDCodecDefault.serialize("TestType", "123")): NodeResolverExecutor.Selector =
        NodeResolverExecutor.Selector(id = id, selections = mockk<EngineSelectionSet>())

    @Test
    fun `resolve returns value from resolver function`(): Unit =
        runBlocking {
            val engineData = mockk<EngineObjectData.Sync>()
            val grt = TestNodeGRT(engineData)
            val executor = JavaNodeResolverExecutorImpl(
                resolver = nodeResolver { CompletableFuture.completedFuture(grt) },
                typeName = "TestType",
                resolverName = "TestNodeResolver",
            )

            val result = executor.resolve(listOf(selector()), mockEngineContext())

            assertEquals(1, result.size)
            val value = result.values.single()
            assertTrue(value.isSuccess)
            assertEquals(engineData, value.getOrNull())
        }

    @Test
    fun `resolve passes serialized id to context`(): Unit =
        runBlocking {
            val serializedId = GlobalIDCodecDefault.serialize("TestType", "abc-456")
            var capturedInternalId: String? = null

            val executor = JavaNodeResolverExecutorImpl(
                resolver = nodeResolver { ctx ->
                    capturedInternalId = ctx.getId().getInternalID()
                    CompletableFuture.completedFuture(TestNodeGRT(mockk<EngineObjectData.Sync>()))
                },
                typeName = "TestType",
                resolverName = "TestNodeResolver",
            )

            executor.resolve(listOf(selector(serializedId)), mockEngineContext())

            assertEquals("abc-456", capturedInternalId)
        }

    @Test
    fun `resolve wraps tenant exception as TenantResolverException`(): Unit =
        runBlocking {
            val failedFuture = CompletableFuture<Any?>()
            failedFuture.completeExceptionally(RuntimeException("node fetch failed"))

            val executor = JavaNodeResolverExecutorImpl(
                resolver = nodeResolver { failedFuture },
                typeName = "TestType",
                resolverName = "TestNodeResolver",
            )

            val result = executor.resolve(listOf(selector()), mockEngineContext())

            assertEquals(1, result.size)
            val value = result.values.single()
            assertTrue(value.isFailure)
            val ex = value.exceptionOrNull().shouldBeInstanceOf<TenantResolverException>()
            assertEquals("node fetch failed", generateSequence(ex.cause) { it.cause }.last().message)
        }

    @Test
    fun `executor has correct metadata`() {
        val executor = JavaNodeResolverExecutorImpl(
            resolver = nodeResolver { CompletableFuture.completedFuture(mockk<EngineObjectData.Sync>()) },
            typeName = "TestType",
            resolverName = "TestNodeResolver",
        )

        assertEquals("TestType", executor.typeName)
        assertEquals("TestNodeResolver", executor.metadata.name)
        assertFalse(executor.isBatching)
        assertFalse(executor.isSelective)
    }

    @Test
    fun `batch executor binds values by context independent of map order`(): Unit =
        runBlocking {
            val id1 = GlobalIDCodecDefault.serialize("TestType", "1")
            val id2 = GlobalIDCodecDefault.serialize("TestType", "2")
            val engineData1 = mockk<EngineObjectData.Sync>()
            val engineData2 = mockk<EngineObjectData.Sync>()

            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver { contexts ->
                    val results = linkedMapOf<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>(
                        contexts[1] to FieldValue.ofValue(TestNodeGRT(engineData2)),
                        contexts[0] to FieldValue.ofValue(TestNodeGRT(engineData1)),
                    )
                    CompletableFuture.completedFuture(results)
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            val selectors = listOf(selector(id1), selector(id2))
            val result = executor.resolve(selectors, mockEngineContext())

            assertEquals(2, result.size)
            assertTrue(result[selectors[0]]!!.isSuccess)
            assertEquals(engineData1, result[selectors[0]]!!.getOrNull())
            assertTrue(result[selectors[1]]!!.isSuccess)
            assertEquals(engineData2, result[selectors[1]]!!.getOrNull())
        }

    @Test
    fun `batch executor surfaces per-element FieldValue ofError as failed Result`(): Unit =
        runBlocking {
            val id1 = GlobalIDCodecDefault.serialize("TestType", "1")
            val id2 = GlobalIDCodecDefault.serialize("TestType", "2")
            val engineData1 = mockk<EngineObjectData.Sync>()

            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver { contexts ->
                    val results = mapOf<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>(
                        contexts[0] to FieldValue.ofValue(TestNodeGRT(engineData1)),
                        contexts[1] to FieldValue.ofError(RuntimeException("boom")),
                    )
                    CompletableFuture.completedFuture(results)
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            val selectors = listOf(selector(id1), selector(id2))
            val result = executor.resolve(selectors, mockEngineContext())

            assertTrue(result[selectors[0]]!!.isSuccess)
            assertTrue(result[selectors[1]]!!.isFailure)
            // The error from FieldValue.ofError is rethrown by FieldValue.get() and wrapped in
            // TenantResolverException (mirrors Kotlin NodeBatchResolverExecutorImpl).
            val ex = result[selectors[1]]!!.exceptionOrNull().shouldBeInstanceOf<TenantResolverException>()
            assertEquals("boom", generateSequence(ex.cause) { it.cause }.last().message)
        }

    @Test
    fun `batch executor has correct metadata`() {
        val executor = NodeBatchResolverExecutorImpl(
            resolver = batchResolver { CompletableFuture.completedFuture(emptyMap()) },
            typeName = "TestType",
            resolverName = "TestBatchNodeResolver",
        )

        assertEquals("TestType", executor.typeName)
        assertTrue(executor.isBatching)
        assertFalse(executor.isSelective)
    }

    @Test
    fun `batch executor rejects omitted context key`(): Unit =
        runBlocking {
            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver { CompletableFuture.completedFuture(emptyMap()) },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            val result = executor.resolve(listOf(selector()), mockEngineContext())

            result.values.single().exceptionOrNull().shouldBeInstanceOf<TenantUsageException>()
        }

    @Test
    fun `resolve fails with TenantUsageException when result is not a ObjectBase GRT`(): Unit =
        runBlocking {
            val executor = JavaNodeResolverExecutorImpl(
                resolver = nodeResolver { CompletableFuture.completedFuture("not-a-grt") },
                typeName = "TestType",
                resolverName = "TestNodeResolver",
            )

            val result = executor.resolve(listOf(selector()), mockEngineContext())
            val value = result.values.single()
            assertTrue(value.isFailure)
            val ex = value.exceptionOrNull().shouldBeInstanceOf<TenantUsageException>()
            assertTrue(ex.message!!.contains("not a GRT for a node object"))
        }

    @Test
    fun `resolve fails with TenantUsageException when result is a NodeReference-backed GRT`(): Unit =
        runBlocking {
            val nodeRef = mockk<NodeReference>()
            val grt = TestNodeGRT(nodeRef)
            val executor = JavaNodeResolverExecutorImpl(
                resolver = nodeResolver { CompletableFuture.completedFuture(grt) },
                typeName = "TestType",
                resolverName = "TestNodeResolver",
            )

            val result = executor.resolve(listOf(selector()), mockEngineContext())
            val value = result.values.single()
            assertTrue(value.isFailure)
            val ex = value.exceptionOrNull().shouldBeInstanceOf<TenantUsageException>()
            assertTrue(ex.message!!.contains("NodeReference returned from node resolver"))
        }

    @Test
    fun `batch executor fails per-element with TenantResolverException when entry value is a NodeReference-backed GRT`(): Unit =
        runBlocking {
            val nodeRef = mockk<NodeReference>()
            val grt = TestNodeGRT(nodeRef)
            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver { contexts ->
                    val results = mapOf(contexts.single() to FieldValue.ofValue(grt))
                    CompletableFuture.completedFuture(results)
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            val result = executor.resolve(listOf(selector()), mockEngineContext())
            val value = result.values.single()
            assertTrue(value.isFailure)
            val ex = value.exceptionOrNull().shouldBeInstanceOf<TenantResolverException>()
            generateSequence(ex.cause) { it.cause }.last().shouldBeInstanceOf<TenantUsageException>()
        }

    @Test
    fun `batch executor rejects context not supplied to current invocation`(): Unit =
        runBlocking {
            val foreignContext = mockk<NodeExecutionContext<*>>()
            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver {
                    CompletableFuture.completedFuture(
                        mapOf(foreignContext to FieldValue.ofValue(TestNodeGRT(mockk<EngineObjectData.Sync>())))
                    )
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            val result = executor.resolve(listOf(selector()), mockEngineContext())

            assertTrue(result.values.single().exceptionOrNull() is TenantUsageException)
        }

    @Test
    fun `batch executor runs duplicate internal id groups concurrently`(): Unit =
        runBlocking {
            val id = GlobalIDCodecDefault.serialize("TestType", "same-id")
            val invocationContexts = mutableListOf<List<NodeExecutionContext<*>>>()
            val invocationResults =
                mutableListOf<CompletableFuture<Map<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>>>()
            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver { contexts ->
                    val result = CompletableFuture<Map<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>>()
                    invocationContexts.add(contexts)
                    invocationResults.add(result)
                    if (invocationContexts.size == 2) {
                        invocationContexts.zip(invocationResults).forEach { (contexts, invocationResult) ->
                            invocationResult.complete(
                                contexts.associateWith {
                                    FieldValue.ofError<TestNodeGRT>(RuntimeException("expected"))
                                }
                            )
                        }
                    }
                    result
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )
            val selectors = listOf(selector(id), selector(id))

            val result = withTimeout(5_000) {
                executor.resolve(selectors, mockEngineContext())
            }

            assertEquals(listOf(1, 1), invocationContexts.map { it.size })
            assertEquals(2, result.size)
        }

    @Test
    fun `batch executor rejects context returned from another split invocation`(): Unit =
        runBlocking {
            val id = GlobalIDCodecDefault.serialize("TestType", "same-id")
            var firstContext: NodeExecutionContext<*>? = null
            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver { contexts ->
                    val currentContext = contexts.single()
                    val returnedContext = firstContext ?: currentContext
                    firstContext = firstContext ?: currentContext
                    CompletableFuture.completedFuture(
                        mapOf(
                            returnedContext to
                                FieldValue.ofError<TestNodeGRT>(RuntimeException("expected"))
                        )
                    )
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            val result = executor.resolve(listOf(selector(id), selector(id)), mockEngineContext())

            assertTrue(result.values.any { it.exceptionOrNull() is TenantUsageException })
        }

    @Test
    fun `batch executor isolates a failing split invocation`(): Unit =
        runBlocking {
            val id = GlobalIDCodecDefault.serialize("TestType", "same-id")
            var invocation = 0
            val executor = NodeBatchResolverExecutorImpl(
                resolver = batchResolver { contexts ->
                    invocation += 1
                    if (invocation == 2) {
                        CompletableFuture.failedFuture<Map<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>>(
                            RuntimeException("second invocation failed")
                        )
                    } else {
                        CompletableFuture.completedFuture(
                            contexts.associateWith {
                                FieldValue.ofError<TestNodeGRT>(RuntimeException("expected"))
                            }
                        )
                    }
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )
            val selectors = listOf(selector(id), selector(id))

            val result = executor.resolve(selectors, mockEngineContext())

            assertEquals(
                "expected",
                generateSequence(result.getValue(selectors.first()).exceptionOrNull()) { it.cause }.last().message,
            )
            assertEquals(
                "second invocation failed",
                generateSequence(result.getValue(selectors.last()).exceptionOrNull()) { it.cause }.last().message,
            )
        }

    private fun batchResolver(
        resolve: (List<NodeExecutionContext<*>>) ->
        CompletableFuture<Map<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>>
    ): Provider<BaseBatchedNodeResolver<TestNodeGRT>> =
        Provider {
            object : BaseBatchedNodeResolver<TestNodeGRT> {
                override fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): CompletableFuture<Map<NodeExecutionContext<*>, FieldValue<TestNodeGRT>>> = resolve(contexts)
            }
        }

    private fun nodeResolver(resolve: (NodeExecutionContext<*>) -> CompletableFuture<*>): Provider<BaseUnbatchedNodeResolver> =
        Provider {
            BaseUnbatchedNodeResolver { context -> resolve(context) }
        }
}
