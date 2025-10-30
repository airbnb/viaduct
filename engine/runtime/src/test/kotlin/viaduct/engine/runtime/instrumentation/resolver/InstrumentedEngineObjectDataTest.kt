package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData

internal class InstrumentedEngineObjectDataTest {
    @Test
    @ExperimentalCoroutinesApi
    fun `fetch calls instrumentation during execution`() =
        runBlockingTest {
            // Given
            val mockEngineObjectData: EngineObjectData = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            val selection = "testField"
            val expectedResult = "testValue"

            coEvery { mockEngineObjectData.fetch(selection) } returns expectedResult

            val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

            // When
            val result = testClass.fetch(selection)

            // Then
            assertEquals(expectedResult, result)
            assertEquals(1, instrumentation.fetchSelectionContexts.size)
            val context = instrumentation.fetchSelectionContexts.first()
            assertEquals(selection, context.parameters.selection)
            assertTrue(context.onCompleted.onCompletedCalled.get())
            assertEquals(expectedResult, context.onCompleted.completedResult)
            assertNull(context.onCompleted.completedException)
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `fetchOrNull calls instrumentation during execution`() =
        runBlockingTest {
            // Given
            val mockEngineObjectData: EngineObjectData = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            val selection = "testField"

            coEvery { mockEngineObjectData.fetchOrNull(selection) } returns null

            val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

            // When
            val resultOrNull = testClass.fetchOrNull(selection)

            // Then
            assertNull(resultOrNull)
            assertEquals(1, instrumentation.fetchSelectionContexts.size)
            val context = instrumentation.fetchSelectionContexts.first()
            assertEquals(selection, context.parameters.selection)
            assertTrue(context.onCompleted.onCompletedCalled.get())
            assertNull(context.onCompleted.completedResult)
            assertNull(context.onCompleted.completedException)
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `fetch succeeds even when beginFetchSelection throws`() =
        runBlockingTest {
            // Given
            val mockEngineObjectData: EngineObjectData = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnBeginFetch = true)
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            val selection = "testField"
            val expectedResult = "testValue"

            coEvery { mockEngineObjectData.fetch(selection) } returns expectedResult

            val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

            // When
            val result = testClass.fetch(selection)

            // Then
            assertEquals(expectedResult, result)
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `fetch succeeds even when onCompleted throws after successful fetch`() =
        runBlockingTest {
            // Given
            val mockEngineObjectData: EngineObjectData = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnCompleted = true)
            val state = instrumentation.createInstrumentationState(mockk())

            val selection = "testField"
            val expectedResult = "testValue"

            coEvery { mockEngineObjectData.fetch(selection) } returns expectedResult

            val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

            // When
            val result = testClass.fetch(selection)

            // Then
            assertEquals(expectedResult, result)
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `fetch failure is propagated even when onCompleted throws`() =
        runBlockingTest {
            // Given
            val mockEngineObjectData: EngineObjectData = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnCompleted = true)
            val state = instrumentation.createInstrumentationState(mockk())

            val selection = "testField"
            val fetchException = RuntimeException("Fetch failed")

            coEvery { mockEngineObjectData.fetch(selection) } throws fetchException

            val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.fetch(selection)
            }
            assertSame(fetchException, thrown)
        }
}
