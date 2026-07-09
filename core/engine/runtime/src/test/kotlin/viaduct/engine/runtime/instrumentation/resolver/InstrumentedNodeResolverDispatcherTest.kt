@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.mocks.ContextMocks

@OptIn(ExperimentalCoroutinesApi::class)
internal class InstrumentedNodeResolverDispatcherTest {
    private val defaultContext: EngineExecutionContextImpl
        get() = ContextMocks().engineExecutionContextImpl

    @Test
    fun `resolve calls instrumentation during execution`() =
        runBlocking {
            // Given
            val mockDispatcher: NodeResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockMetadata: ResolverMetadata = ResolverMetadata.forMock("mock-resolver")
            val mockResult: EngineObjectData = mockk()

            every { mockDispatcher.resolverMetadata } returns mockMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any()) } returns mockResult

            val testClass = InstrumentedNodeResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve("id123", mockk(), defaultContext)

            // Then
            assertSame(mockResult, result)
            assertEquals(1, instrumentation.executeResolverContexts.size)
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(mockMetadata, executeContext.parameters.resolverMetadata)
            assertEquals(mockResult, executeContext.result)
            assertNull(executeContext.error)
        }

    @Test
    fun `resolve calls instrumentation with error on exception`() =
        runBlocking {
            // Given
            val mockDispatcher: NodeResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockMetadata: ResolverMetadata = ResolverMetadata.forMock("mock-resolver")
            val exception = RuntimeException("test error")

            every { mockDispatcher.resolverMetadata } returns mockMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any()) } throws exception

            val testClass = InstrumentedNodeResolverDispatcher(mockDispatcher, instrumentation)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.resolve("id123", mockk(), defaultContext)
            }
            assertSame(exception, thrown)

            // Verify instrumentation recorded the error
            assertEquals(1, instrumentation.executeResolverContexts.size)
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(mockMetadata, executeContext.parameters.resolverMetadata)
            assertNull(executeContext.result)
            assertSame(exception, executeContext.error)
        }

    @Test
    fun `resolve propagates instrumentation exceptions during execution`() =
        runBlocking {
            // Given
            val mockDispatcher: NodeResolverDispatcher = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnInstrumentExecute = true)
            val mockMetadata: ResolverMetadata = ResolverMetadata.forMock("mock-resolver")

            every { mockDispatcher.resolverMetadata } returns mockMetadata

            val testClass = InstrumentedNodeResolverDispatcher(mockDispatcher, instrumentation)

            // Make sure the exception is propagated to the top level when the instrumentation decides to throw
            assertThrows<RuntimeException> {
                testClass.resolve("id123", mockk(), defaultContext)
            }
        }

    @Test
    fun `resolve stamps resolver attribution on context passed to dispatcher`() =
        runBlocking {
            // Given
            val resolverName = "my-node-resolver"
            val mockDispatcher: NodeResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockMetadata: ResolverMetadata = ResolverMetadata.forMock(resolverName)
            val mockResult: EngineObjectData = mockk()
            val capturedContext = slot<EngineExecutionContext>()

            every { mockDispatcher.resolverMetadata } returns mockMetadata
            coEvery { mockDispatcher.resolve(any(), any(), capture(capturedContext)) } returns mockResult

            val testClass = InstrumentedNodeResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve("id123", mockk(), defaultContext)

            // Then - the context passed to the underlying dispatcher must carry the resolver's attribution
            assertEquals(
                ExecutionAttribution.fromResolver(resolverName),
                capturedContext.captured.fieldScope.attribution
            )
        }

    @Test
    fun `resolve wraps context in InstrumentedEngineExecutionContext`() =
        runBlocking {
            // Given
            val mockDispatcher: NodeResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val capturedContext = slot<EngineExecutionContext>()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery { mockDispatcher.resolve(any(), any(), capture(capturedContext)) } returns mockk()

            val testClass = InstrumentedNodeResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve("id123", mockk(), defaultContext)

            // Then - context must be wrapped for resolveSelectionSet instrumentation
            assertTrue(capturedContext.captured is InstrumentedEngineExecutionContext)
        }

    @Test
    fun `resolve wraps context in InstrumentedEngineExecutionContext even for DEFAULT instrumentation`() =
        runBlocking {
            // Given — wrapping is unconditional
            val mockDispatcher: NodeResolverDispatcher = mockk()
            val capturedContext = slot<EngineExecutionContext>()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery { mockDispatcher.resolve(any(), any(), capture(capturedContext)) } returns mockk()

            val testClass = InstrumentedNodeResolverDispatcher(mockDispatcher, ViaductResolverInstrumentation.DEFAULT)

            // When
            testClass.resolve("id123", mockk(), defaultContext)

            // Then
            assertTrue(capturedContext.captured is InstrumentedEngineExecutionContext)
        }
}
