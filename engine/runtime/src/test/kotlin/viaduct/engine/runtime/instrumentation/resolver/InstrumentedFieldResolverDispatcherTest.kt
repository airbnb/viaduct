package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.FieldResolverDispatcher
import viaduct.engine.api.ResolverMetadata

@OptIn(ExperimentalCoroutinesApi::class)
internal class InstrumentedFieldResolverDispatcherTest {
    @Test
    fun `resolve calls instrumentation during execution`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata = ResolverMetadata.forMock("mock-resolver")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())

            // Then
            assertEquals("result", result)
            assertEquals(1, instrumentation.executeResolverContexts.size)
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(mockResolverMetadata, executeContext.parameters.resolverMetadata)
            assertEquals("result", executeContext.result)
            assertNull(executeContext.error)
        }

    @Test
    fun `resolve calls instrumentation with error on exception`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata = ResolverMetadata.forMock("mock-resolver")
            val exception = RuntimeException("test error")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } throws exception

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())
            }
            assertSame(exception, thrown)

            // Verify instrumentation recorded the error
            assertEquals(1, instrumentation.executeResolverContexts.size)
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(mockResolverMetadata, executeContext.parameters.resolverMetadata)
            assertNull(executeContext.result)
            assertSame(exception, executeContext.error)
        }

    @Test
    fun `resolve propagates instrumentation exceptions during execution`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnInstrumentExecute = true)
            val mockResolverMetadata: ResolverMetadata = ResolverMetadata.forMock("mock-resolver")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // Make sure the exception is propogated to the top level when the instrumentation decides to throw
            assertThrows<RuntimeException> {
                testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())
            }
        }
}
