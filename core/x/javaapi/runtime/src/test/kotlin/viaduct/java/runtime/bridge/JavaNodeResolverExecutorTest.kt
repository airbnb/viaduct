@file:Suppress("ForbiddenImport")

package viaduct.java.runtime.bridge

import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.NodeReference
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.errors.TenantResolverException
import viaduct.errors.TenantUsageException
import viaduct.java.api.internal.ObjectBase
import viaduct.java.api.resolvers.FieldValue
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

private class TestNodeGRT : ObjectBase {
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
                resolveFunction = { CompletableFuture.completedFuture(grt) },
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
                resolveFunction = { ctx ->
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
                resolveFunction = { failedFuture },
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
            resolveFunction = { CompletableFuture.completedFuture(mockk<EngineObjectData.Sync>()) },
            typeName = "TestType",
            resolverName = "TestNodeResolver",
        )

        assertEquals("TestType", executor.typeName)
        assertEquals("TestNodeResolver", executor.metadata.name)
        assertFalse(executor.isBatching)
        assertFalse(executor.isSelective)
    }

    @Test
    fun `batch executor resolve returns values for all selectors in order`(): Unit =
        runBlocking {
            val id1 = GlobalIDCodecDefault.serialize("TestType", "1")
            val id2 = GlobalIDCodecDefault.serialize("TestType", "2")
            val engineData1 = mockk<EngineObjectData.Sync>()
            val engineData2 = mockk<EngineObjectData.Sync>()

            val executor = NodeBatchResolverExecutorImpl(
                batchResolveFunction = { _ ->
                    val results = listOf<FieldValue<ObjectBase>>(
                        FieldValue.ofValue(TestNodeGRT(engineData1)),
                        FieldValue.ofValue(TestNodeGRT(engineData2)),
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
                batchResolveFunction = { _ ->
                    val results = listOf<FieldValue<ObjectBase>>(
                        FieldValue.ofValue(TestNodeGRT(engineData1)),
                        FieldValue.ofError(RuntimeException("boom")),
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
            batchResolveFunction = { CompletableFuture.completedFuture(emptyList<FieldValue<*>>()) },
            typeName = "TestType",
            resolverName = "TestBatchNodeResolver",
        )

        assertEquals("TestType", executor.typeName)
        assertTrue(executor.isBatching)
        assertFalse(executor.isSelective)
    }

    @Test
    fun `batch executor throws when result size mismatches selectors`(): Unit =
        runBlocking {
            val executor = NodeBatchResolverExecutorImpl(
                batchResolveFunction = { _ ->
                    CompletableFuture.completedFuture(emptyList<FieldValue<*>>())
                },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            assertThrows<TenantUsageException> {
                executor.resolve(listOf(selector()), mockEngineContext())
            }
        }

    @Test
    fun `batch executor throws when result is not a List`(): Unit =
        runBlocking {
            val executor = NodeBatchResolverExecutorImpl(
                batchResolveFunction = { _ -> CompletableFuture.completedFuture("not-a-list") },
                typeName = "TestType",
                resolverName = "TestBatchNodeResolver",
            )

            assertThrows<TenantUsageException> {
                executor.resolve(listOf(selector()), mockEngineContext())
            }
        }

    @Test
    fun `resolve fails with TenantUsageException when result is not a ObjectBase GRT`(): Unit =
        runBlocking {
            val executor = JavaNodeResolverExecutorImpl(
                resolveFunction = { CompletableFuture.completedFuture("not-a-grt") },
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
                resolveFunction = { CompletableFuture.completedFuture(grt) },
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
    fun `batch executor fails per-element with TenantResolverException when entry is not a FieldValue`(): Unit =
        runBlocking {
            val executor = NodeBatchResolverExecutorImpl(
                batchResolveFunction = { _ ->
                    CompletableFuture.completedFuture(listOf("not-a-field-value"))
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
    fun `batch executor fails per-element with TenantResolverException when entry value is not a ObjectBase`(): Unit =
        runBlocking {
            val executor = NodeBatchResolverExecutorImpl(
                batchResolveFunction = { _ ->
                    val results = listOf<FieldValue<Any>>(FieldValue.ofValue("not-a-grt"))
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
    fun `batch executor fails per-element with TenantResolverException when entry value is a NodeReference-backed GRT`(): Unit =
        runBlocking {
            val nodeRef = mockk<NodeReference>()
            val grt = TestNodeGRT(nodeRef)
            val executor = NodeBatchResolverExecutorImpl(
                batchResolveFunction = { _ ->
                    val results = listOf<FieldValue<ObjectBase>>(FieldValue.ofValue(grt))
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
}
