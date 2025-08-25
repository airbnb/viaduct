package viaduct.engine.runtime

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerDispatcher
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeResolverDispatcher
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class NodeEngineObjectDataImplTest {
    private val schema = MockSchema.mk(
        """
        type Query { empty: Int }
        interface Node { id: ID! }
        type TestType implements Node { id: ID! }
        """.trimIndent()
    )
    private val testType = schema.schema.getObjectType("TestType")
    private lateinit var context: EngineExecutionContext
    private lateinit var selections: RawSelectionSet
    private lateinit var dispatcherRegistry: DispatcherRegistry
    private lateinit var nodeResolver: NodeResolverDispatcher
    private lateinit var nodeReference: NodeEngineObjectDataImpl
    private lateinit var engineObjectData: EngineObjectData
    private lateinit var nodeChecker: CheckerDispatcher

    @BeforeEach
    fun setUp() {
        selections = mockk<RawSelectionSet>()
        dispatcherRegistry = mockk<DispatcherRegistry>()
        context = ContextMocks(
            myFullSchema = schema,
            myDispatcherRegistry = dispatcherRegistry,
            myFlagManager = MockFlagManager()
        ).engineExecutionContext
        nodeResolver = mockk<NodeResolverDispatcher>()
        nodeChecker = mockk<CheckerDispatcher>()
        engineObjectData = mockk<EngineObjectData>()
        nodeReference = NodeEngineObjectDataImpl("testID", testType, dispatcherRegistry, dispatcherRegistry)
    }

    @Test
    fun testFetchID() =
        runBlockingTest {
            assertEquals("testID", nodeReference.fetch("id"))
        }

    @Test
    fun testFetchSuspendsWaitingOnResolve() =
        runBlockingTest {
            every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(nodeResolver)
            coEvery { nodeResolver.resolve("testID", selections, context) }.returns(engineObjectData)
            coEvery { engineObjectData.fetch("name") }.returns("testName")
            every { dispatcherRegistry.getTypeCheckerDispatcher("TestType") }.returns(null)

            nodeReference.resolveData(selections, context)

            assertEquals("testName", nodeReference.fetch("name"))
        }

    @Test
    fun testNodeCheckerRun() =
        runBlockingTest {
            every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(nodeResolver)
            coEvery { nodeResolver.resolve("testID", selections, context) }.returns(engineObjectData)
            coEvery { engineObjectData.fetch("name") }.returns("testName")
            every { dispatcherRegistry.getTypeCheckerDispatcher("TestType") }.returns(nodeChecker)
            coEvery { nodeChecker.execute(any(), any(), any()) }.returns(CheckerResult.Success)

            nodeReference.resolveData(selections, context)

            assertEquals("testName", nodeReference.fetch("name"))
        }

    @Test
    fun testNodeResolverNotFound() =
        runBlockingTest {
            every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(null)

            assertThrows<IllegalStateException> {
                nodeReference.resolveData(selections, context)
            }

            assertThrows<IllegalStateException> {
                nodeReference.fetch("foo")
            }
        }

    @Test
    fun testNodeCheckerFailsThrow() =
        runBlockingTest {
            every { dispatcherRegistry.getNodeResolverDispatcher("TestType") }.returns(nodeResolver)
            coEvery { nodeResolver.resolve("testID", selections, context) }.returns(engineObjectData)
            coEvery { engineObjectData.fetch("name") }.returns("testName")
            every { dispatcherRegistry.getTypeCheckerDispatcher("TestType") }.returns(nodeChecker)
            coEvery { nodeChecker.execute(any(), any(), any()) }.returns(MockCheckerErrorResult(RuntimeException("test")))

            assertThrows<RuntimeException> {
                nodeReference.resolveData(selections, context)
            }
            assertThrows<RuntimeException> {
                nodeReference.fetch("foo")
            }
        }
}
