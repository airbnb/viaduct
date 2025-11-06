@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.NodeResolverDispatcher
import viaduct.engine.api.ResolverMetadata

@OptIn(ExperimentalCoroutinesApi::class)
internal class InstrumentedNodeResolverDispatcherTest {
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
            val result = testClass.resolve("id123", mockk(), mockk())

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
                testClass.resolve("id123", mockk(), mockk())
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
                testClass.resolve("id123", mockk(), mockk())
            }
        }
}
