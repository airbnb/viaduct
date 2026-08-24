@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.errors.UnsetFieldException
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class NodeEngineObjectDataImplTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { empty: Int }
        type TestType implements Node { id: ID!, name: String, age: Int, nickname: String }
        """.trimIndent()
    )
    private val testType = schema.schema.getObjectType("TestType")
    private lateinit var context: EngineExecutionContext
    private lateinit var selections: EngineSelectionSet
    private lateinit var dispatcherRegistry: DispatcherRegistry
    private lateinit var nodeResolver: NodeResolverDispatcher
    private lateinit var nodeReference: NodeEngineObjectDataImpl
    private lateinit var engineObjectData: EngineObjectData

    @BeforeEach
    fun setUp() {
        selections = mockk<EngineSelectionSet>()
        dispatcherRegistry = mockk<DispatcherRegistry>()
        context = ContextMocks(
            myFullSchema = schema,
            myDispatcherRegistry = dispatcherRegistry,
            myFlagManager = MockFlagManager()
        ).engineExecutionContext
        nodeResolver = mockk<NodeResolverDispatcher>()
        every { nodeResolver.resolverMetadata } returns ResolverMetadata.forMock("test-node-resolver")
        every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(nodeResolver)
        engineObjectData = mockk<EngineObjectData>()
        nodeReference = NodeEngineObjectDataImpl("testID", testType, dispatcherRegistry)
    }

    @Test
    fun testFetchID(): Unit =
        runBlocking {
            assertEquals("testID", nodeReference.fetch("id"))
        }

    @Test
    fun `first resolution provides data used by fetch`(): Unit =
        runBlocking {
            coEvery { nodeResolver.resolve("testID", selections, any()) }.returns(engineObjectData)
            coEvery { engineObjectData.fetchSelections() }.returns(listOf("name"))
            coEvery { engineObjectData.fetch("name") }.returns("testName")

            val result = nodeReference.resolveData(selections, context)

            assertSame(engineObjectData, result)
            assertEquals("testName", nodeReference.fetch("name"))
            coVerify(exactly = 1) { nodeResolver.resolve("testID", selections, any()) }
        }

    @Test
    fun `lazy object cycle fails resolution`(): Unit =
        runBlocking {
            val lazyData = mockk<LazyEngineObjectData>()
            coEvery { nodeResolver.resolve("testID", selections, any()) }.returns(lazyData)
            coEvery { lazyData.resolveData(selections, any()) }.returns(lazyData)

            val error = assertThrows<IllegalStateException> {
                nodeReference.resolveData(selections, context)
            }

            assertEquals(
                "Node resolver for TestType(testID) returned a cycle of lazy object references",
                error.message,
            )
        }

    @Test
    fun testNodeResolverNotFound(): Unit =
        runBlocking {
            every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(null)

            assertThrows<IllegalStateException> {
                nodeReference.resolveData(selections, context)
            }

            assertThrows<IllegalStateException> {
                nodeReference.fetch("foo")
            }
        }

    @Test
    fun `later resolutions add fields to the node reference`(): Unit =
        runBlocking {
            val nameSelections = selections("name")
            val ageSelections = selections("age")
            val nameData = data("name" to "testName")
            val ageData = data("age" to 42)
            coEvery { nodeResolver.resolve("testID", nameSelections, any()) }.returns(nameData)
            coEvery { nodeResolver.resolve("testID", ageSelections, any()) }.returns(ageData)

            val result1 = nodeReference.resolveData(nameSelections, context)
            val result2 = nodeReference.resolveData(ageSelections, context)

            assertSame(nameData, result1)
            assertSame(ageData, result2)
            assertEquals("testName", nodeReference.fetch("name"))
            assertEquals(42, nodeReference.fetch("age"))
            assertEquals(setOf("name", "age"), nodeReference.fetchSelections().toSet())
            coVerify(exactly = 1) { nodeResolver.resolve("testID", nameSelections, any()) }
            coVerify(exactly = 1) { nodeResolver.resolve("testID", ageSelections, any()) }
        }

    @Test
    fun `concurrent resolutions retain both results`() =
        runTest {
            val nameSelections = selections("name")
            val ageSelections = selections("age")
            val nameData = data("name" to "testName")
            val ageData = data("age" to 42)
            val nameStarted = CompletableDeferred<Unit>()
            val ageStarted = CompletableDeferred<Unit>()
            val releaseResolvers = CompletableDeferred<Unit>()
            coEvery { nodeResolver.resolve("testID", nameSelections, any()) } coAnswers {
                nameStarted.complete(Unit)
                releaseResolvers.await()
                nameData
            }
            coEvery { nodeResolver.resolve("testID", ageSelections, any()) } coAnswers {
                ageStarted.complete(Unit)
                releaseResolvers.await()
                ageData
            }

            val nameResolution = async { nodeReference.resolveData(nameSelections, context) }
            val ageResolution = async { nodeReference.resolveData(ageSelections, context) }
            withTimeout(1.seconds) {
                nameStarted.await()
                ageStarted.await()
            }
            releaseResolvers.complete(Unit)

            assertSame(nameData, nameResolution.await())
            assertSame(ageData, ageResolution.await())
            assertEquals("testName", nodeReference.fetch("name"))
            assertEquals(42, nodeReference.fetch("age"))
        }

    @Test
    fun `concurrent success remains readable when first resolution fails`() =
        runTest {
            val nameSelections = selections("name")
            val ageSelections = selections("age")
            val ageData = data("age" to 42)
            val failure = IllegalStateException("name failed")
            val nameStarted = CompletableDeferred<Unit>()
            val releaseName = CompletableDeferred<Unit>()
            coEvery { nodeResolver.resolve("testID", nameSelections, any()) } coAnswers {
                nameStarted.complete(Unit)
                releaseName.await()
                throw failure
            }
            coEvery { nodeResolver.resolve("testID", ageSelections, any()) }.returns(ageData)

            val failedResolution = async {
                val thrown = assertThrows<IllegalStateException> {
                    nodeReference.resolveData(nameSelections, context)
                }
                assertSame(failure, thrown)
            }
            withTimeout(1.seconds) {
                nameStarted.await()
            }
            val pendingFetch = async(start = CoroutineStart.UNDISPATCHED) {
                nodeReference.fetch("age")
            }
            assertFalse(pendingFetch.isCompleted)

            assertSame(ageData, nodeReference.resolveData(ageSelections, context))
            assertEquals(42, pendingFetch.await())

            releaseName.complete(Unit)
            failedResolution.await()
            assertEquals(42, nodeReference.fetch("age"))
        }

    @Test
    fun `later resolution failure preserves earlier fields`(): Unit =
        runBlocking {
            val nameSelections = selections("name")
            val ageSelections = selections("age")
            val nameData = data("name" to "testName")
            val failure = IllegalStateException("age failed")
            coEvery { nodeResolver.resolve("testID", nameSelections, any()) }.returns(nameData)
            coEvery { nodeResolver.resolve("testID", ageSelections, any()) }.throws(failure)

            nodeReference.resolveData(nameSelections, context)
            val thrown = assertThrows<IllegalStateException> {
                nodeReference.resolveData(ageSelections, context)
            }

            assertSame(failure, thrown)
            assertEquals("testName", nodeReference.fetch("name"))
            assertEquals(setOf("name"), nodeReference.fetchSelections().toSet())
        }

    @Test
    fun `successful resolution after initial failure becomes readable`(): Unit =
        runBlocking {
            val nameSelections = selections("name")
            val ageSelections = selections("age")
            val ageData = data("age" to 42)
            coEvery {
                nodeResolver.resolve("testID", nameSelections, any())
            }.throws(IllegalStateException("name failed"))
            coEvery { nodeResolver.resolve("testID", ageSelections, any()) }.returns(ageData)

            assertThrows<IllegalStateException> {
                nodeReference.resolveData(nameSelections, context)
            }
            val result = nodeReference.resolveData(ageSelections, context)

            assertSame(ageData, result)
            assertEquals(42, nodeReference.fetch("age"))
            assertEquals(setOf("age"), nodeReference.fetchSelections().toSet())
        }

    @Test
    fun `fetch waits for first materialization`() =
        runTest {
            val nameSelections = selections("name")
            val nameData = data("name" to "testName")
            coEvery { nodeResolver.resolve("testID", nameSelections, any()) }.returns(nameData)
            val pendingFetch = async(start = CoroutineStart.UNDISPATCHED) {
                nodeReference.fetch("name")
            }

            assertFalse(pendingFetch.isCompleted)
            nodeReference.resolveData(nameSelections, context)

            assertEquals("testName", pendingFetch.await())
        }

    @Test
    fun `present null remains distinguishable from an unset field`(): Unit =
        runBlocking {
            val nicknameSelections = selections("nickname")
            val nicknameData = data("nickname" to null)
            coEvery {
                nodeResolver.resolve("testID", nicknameSelections, any())
            }.returns(nicknameData)

            nodeReference.resolveData(nicknameSelections, context)

            assertNull(nodeReference.fetch("nickname"))
            assertNull(nodeReference.fetchOrNull("nickname"))
            assertTrue("nickname" in nodeReference.fetchSelections())
        }

    @Test
    fun `missing fields retain engine object data behavior`(): Unit =
        runBlocking {
            val nameSelections = selections("name")
            val nameData = data("name" to "testName")
            coEvery { nodeResolver.resolve("testID", nameSelections, any()) }.returns(nameData)
            nodeReference.resolveData(nameSelections, context)

            assertThrows<UnsetFieldException> {
                nodeReference.fetch("missing")
            }
            assertNull(nodeReference.fetchOrNull("missing"))
        }

    @Test
    fun `overlapping materializations use the first value`(): Unit =
        runBlocking {
            val nameSelections = selections("name")
            val firstData = data("name" to "first")
            val secondData = data("name" to "second")
            var calls = 0
            coEvery { nodeResolver.resolve("testID", nameSelections, any()) } coAnswers {
                if (calls++ == 0) firstData else secondData
            }

            assertSame(firstData, nodeReference.resolveData(nameSelections, context))
            assertSame(secondData, nodeReference.resolveData(nameSelections, context))

            assertEquals("first", nodeReference.fetch("name"))
            assertEquals(setOf("name"), nodeReference.fetchSelections().toSet())
        }

    private fun selections(fields: String): EngineSelectionSet = context.engineSelectionSetFactory.engineSelectionSet("TestType", fields, emptyMap())

    private fun data(vararg fields: Pair<String, Any?>): ResolvedEngineObjectData {
        val builder = ResolvedEngineObjectData.Builder(testType)
        fields.forEach { (selection, value) -> builder.put(selection, value) }
        return builder.build()
    }
}
