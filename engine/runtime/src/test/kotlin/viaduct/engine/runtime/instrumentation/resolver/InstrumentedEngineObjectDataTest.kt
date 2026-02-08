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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData

internal class InstrumentedEngineObjectDataTest {
    @Test
    @ExperimentalCoroutinesApi
    fun `fetch calls instrumentation during execution`() =
        runBlocking {
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
            assertEquals(expectedResult, context.result)
            assertNull(context.error)
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `fetchOrNull calls instrumentation during execution`() =
        runBlocking {
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
            assertNull(context.result)
            assertNull(context.error)
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `fetch propagates instrumentation exceptions`() =
        runBlocking {
            // Given
            val mockEngineObjectData: EngineObjectData = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnInstrumentFetch = true)
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

            // When / Then
            // Instrumentation implementations are responsible for being defensive.
            // If they throw, the exception propagates.
            assertThrows<RuntimeException> {
                testClass.fetch("testField")
            }
        }

    @Test
    @ExperimentalCoroutinesApi
    fun `fetch propagates fetch exceptions`() =
        runBlocking {
            // Given
            val mockEngineObjectData: EngineObjectData = mockk()
            val instrumentation = RecordingResolverInstrumentation()
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

            // Verify instrumentation recorded the error
            assertEquals(1, instrumentation.fetchSelectionContexts.size)
            val context = instrumentation.fetchSelectionContexts.first()
            assertNull(context.result)
            assertSame(fetchException, context.error)
        }

    @Nested
    inner class SyncTests {
        @Test
        fun `get calls instrumentation during execution`() {
            // Given
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            val selection = "testField"
            val expectedResult = "testValue"

            every { mockEngineObjectData.get(selection) } returns expectedResult

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            // When
            val result = testClass.get(selection)

            // Then
            assertEquals(expectedResult, result)
            assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
            val context = instrumentation.syncFetchSelectionContexts.first()
            assertEquals(selection, context.parameters.selection)
            assertEquals(expectedResult, context.result)
            assertNull(context.error)
        }

        @Test
        fun `getOrNull calls instrumentation during execution`() {
            // Given
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            val selection = "testField"

            every { mockEngineObjectData.getOrNull(selection) } returns null

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            // When
            val resultOrNull = testClass.getOrNull(selection)

            // Then
            assertNull(resultOrNull)
            assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
            val context = instrumentation.syncFetchSelectionContexts.first()
            assertEquals(selection, context.parameters.selection)
            assertNull(context.result)
            assertNull(context.error)
        }

        @Test
        fun `get propagates instrumentation exceptions`() {
            // Given
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnInstrumentSyncFetch = true)
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            // When / Then
            // Instrumentation implementations are responsible for being defensive.
            // If they throw, the exception propagates.
            assertThrows<RuntimeException> {
                testClass.get("testField")
            }
        }

        @Test
        fun `get propagates get exceptions`() {
            // Given
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(mockk())

            val selection = "testField"
            val getException = RuntimeException("Get failed")

            every { mockEngineObjectData.get(selection) } throws getException

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.get(selection)
            }
            assertSame(getException, thrown)

            // Verify instrumentation recorded the error
            assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
            val context = instrumentation.syncFetchSelectionContexts.first()
            assertNull(context.result)
            assertSame(getException, context.error)
        }

        @Test
        @ExperimentalCoroutinesApi
        fun `fetch delegates to get with instrumentation`() =
            runBlocking {
                // Given
                val mockEngineObjectData: EngineObjectData.Sync = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(
                    parameters = mockk()
                )

                val selection = "testField"
                val expectedResult = "testValue"

                every { mockEngineObjectData.get(selection) } returns expectedResult

                val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

                // When
                val result = testClass.fetch(selection)

                // Then
                assertEquals(expectedResult, result)
                assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
                val context = instrumentation.syncFetchSelectionContexts.first()
                assertEquals(selection, context.parameters.selection)
                assertEquals(expectedResult, context.result)
            }

        @Test
        @ExperimentalCoroutinesApi
        fun `fetchOrNull delegates to getOrNull with instrumentation`() =
            runBlocking {
                // Given
                val mockEngineObjectData: EngineObjectData.Sync = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(
                    parameters = mockk()
                )

                val selection = "testField"

                every { mockEngineObjectData.getOrNull(selection) } returns null

                val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

                // When
                val resultOrNull = testClass.fetchOrNull(selection)

                // Then
                assertNull(resultOrNull)
                assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
                val context = instrumentation.syncFetchSelectionContexts.first()
                assertEquals(selection, context.parameters.selection)
                assertNull(context.result)
            }
    }
}
