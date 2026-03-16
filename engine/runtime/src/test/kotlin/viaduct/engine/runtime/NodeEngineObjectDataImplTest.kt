@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class NodeEngineObjectDataImplTest {
    private val schema = MockSchema.mk(
        """
        extend type Query { empty: Int }
        type TestType implements Node { id: ID! }
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
        engineObjectData = mockk<EngineObjectData>()
        nodeReference = NodeEngineObjectDataImpl("testID", testType, dispatcherRegistry)
    }

    @Test
    fun testFetchID(): Unit =
        runBlocking {
            assertEquals("testID", nodeReference.fetch("id"))
        }

    @Test
    fun testFetchSuspendsWaitingOnResolve(): Unit =
        runBlocking {
            every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(nodeResolver)
            coEvery { nodeResolver.resolve("testID", selections, context) }.returns(engineObjectData)
            coEvery { engineObjectData.fetch("name") }.returns("testName")

            nodeReference.resolveData(selections, context)

            assertEquals("testName", nodeReference.fetch("name"))
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
    fun `resolveData called twice`(): Unit =
        runBlocking {
            every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(nodeResolver)
            coEvery { nodeResolver.resolve("testID", selections, context) }.returns(engineObjectData)
            coEvery { engineObjectData.fetch("name") }.returns("testName")

            val result1 = nodeReference.resolveData(selections, context)
            assertEquals(true, result1)

            val result2 = nodeReference.resolveData(selections, context)
            assertEquals(false, result2)
            coVerify(exactly = 1) { nodeResolver.resolve("testID", selections, context) }

            assertEquals("testName", nodeReference.fetch("name"))
        }
}
