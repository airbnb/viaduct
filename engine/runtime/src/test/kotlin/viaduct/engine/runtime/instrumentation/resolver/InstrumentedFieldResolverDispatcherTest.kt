package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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
            val mockResolverMetadata: ResolverMetadata = mockk()

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())

            // Then
            assertEquals("result", result)
            assertEquals(1, instrumentation.executeResolverContexts.size)
            val executeContext = instrumentation.executeResolverContexts.first()
            assertTrue(executeContext.onCompleted.onCompletedCalled.get())
            assertEquals("result", executeContext.onCompleted.completedResult)
            assertNull(executeContext.onCompleted.completedException)
        }

    @Test
    fun `resolve calls instrumentation with error on exception`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata: ResolverMetadata = mockk()
            val exception = RuntimeException("test error")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } throws exception

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())
            }
            assertSame(exception, thrown)

            assertEquals(1, instrumentation.executeResolverContexts.size)
            val executeContext = instrumentation.executeResolverContexts.first()
            assertTrue(executeContext.onCompleted.onCompletedCalled.get())
            assertNull(executeContext.onCompleted.completedResult)
            assertSame(exception, executeContext.onCompleted.completedException)
        }

    @Test
    fun `resolve succeeds even when createInstrumentationState throws`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnCreateState = true)
            val mockResolverMetadata: ResolverMetadata = mockk()

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())

            // Then
            assertEquals("result", result)
        }

    @Test
    fun `resolve succeeds even when beginExecuteResolver throws`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnBeginExecute = true)
            val mockResolverMetadata: ResolverMetadata = mockk()

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())

            // Then
            assertEquals("result", result)
        }

    @Test
    fun `resolve succeeds even when onCompleted throws after successful resolve`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnCompleted = true)
            val mockResolverMetadata: ResolverMetadata = mockk()

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())

            // Then
            assertEquals("result", result)
        }

    @Test
    fun `resolve failure is propagated even when onCompleted throws`() =
        runBlockingTest {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnCompleted = true)
            val mockResolverMetadata: ResolverMetadata = mockk()
            val resolveException = RuntimeException("Resolve failed")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } throws resolveException

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.resolve(emptyMap(), mockk(), mockk(), null, mockk())
            }
            assertSame(resolveException, thrown)
        }
}
