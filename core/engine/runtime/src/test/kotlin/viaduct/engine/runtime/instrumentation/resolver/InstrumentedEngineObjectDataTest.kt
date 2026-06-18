@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import graphql.schema.GraphQLObjectType
import io.kotest.matchers.types.shouldBeInstanceOf
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
    private val testGraphQLObjectType = GraphQLObjectType.newObject().name("TestType").build()

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

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            coEvery { mockEngineObjectData.fetch(selection) } returns expectedResult

            val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

            // When
            val result = testClass.fetch(selection)

            // Then
            assertEquals(expectedResult, result)
            assertEquals(1, instrumentation.fetchSelectionContexts.size)
            val context = instrumentation.fetchSelectionContexts.first()
            assertEquals(selection, context.parameters.selection)
            assertEquals("TestType", context.parameters.parentTypeName)
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

            every { mockEngineObjectData.type } returns testGraphQLObjectType
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

            every { mockEngineObjectData.type } returns testGraphQLObjectType
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

            every { mockEngineObjectData.type } returns testGraphQLObjectType
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
    inner class NestedEngineObjectDataWrappingTests {
        @Test
        @ExperimentalCoroutinesApi
        fun `fetch wraps returned EngineObjectData with InstrumentedEngineObjectData`() =
            runBlocking {
                // Given
                val nestedEngineObjectData: EngineObjectData = mockk()
                val mockEngineObjectData: EngineObjectData = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(parameters = mockk())

                every { mockEngineObjectData.type } returns testGraphQLObjectType
                every { nestedEngineObjectData.type } returns testGraphQLObjectType
                coEvery { mockEngineObjectData.fetch("nestedField") } returns nestedEngineObjectData

                val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

                // When
                val result = testClass.fetch("nestedField")

                // Then
                result.shouldBeInstanceOf<InstrumentedEngineObjectData>()
                assertSame(nestedEngineObjectData, result.engineObjectData)
            }

        @Test
        @ExperimentalCoroutinesApi
        fun `fetch wraps EngineObjectData values inside a list`() =
            runBlocking {
                // Given
                val nestedEngineObjectData: EngineObjectData = mockk()
                val mockEngineObjectData: EngineObjectData = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(parameters = mockk())

                every { mockEngineObjectData.type } returns testGraphQLObjectType
                every { nestedEngineObjectData.type } returns testGraphQLObjectType
                coEvery { mockEngineObjectData.fetch("listField") } returns listOf(nestedEngineObjectData)

                val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

                // When
                val result = testClass.fetch("listField") as List<*>

                // Then
                assertEquals(1, result.size)
                result[0].shouldBeInstanceOf<InstrumentedEngineObjectData>()
                assertSame(nestedEngineObjectData, (result[0] as InstrumentedEngineObjectData).engineObjectData)
            }

        @Test
        @ExperimentalCoroutinesApi
        fun `fetch returns original list unchanged when no elements need wrapping`() =
            runBlocking {
                // Given
                val mockEngineObjectData: EngineObjectData = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(parameters = mockk())
                val scalarList = listOf("a", "b", "c")

                every { mockEngineObjectData.type } returns testGraphQLObjectType
                coEvery { mockEngineObjectData.fetch("scalarListField") } returns scalarList

                val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

                // When
                val result = testClass.fetch("scalarListField")

                // Then — same instance returned, no new list allocated
                assertSame(scalarList, result)
            }

        @Test
        @ExperimentalCoroutinesApi
        fun `fetch does not double-wrap an already instrumented EngineObjectData`() =
            runBlocking {
                // Given
                val innerEngineObjectData: EngineObjectData = mockk()
                val alreadyInstrumented = InstrumentedEngineObjectData(innerEngineObjectData, RecordingResolverInstrumentation(), mockk())
                val mockEngineObjectData: EngineObjectData = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(parameters = mockk())

                every { mockEngineObjectData.type } returns testGraphQLObjectType
                every { innerEngineObjectData.type } returns testGraphQLObjectType
                coEvery { mockEngineObjectData.fetch("field") } returns alreadyInstrumented

                val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

                // When
                val result = testClass.fetch("field")

                // Then
                assertSame(alreadyInstrumented, result)
            }

        @Test
        @ExperimentalCoroutinesApi
        fun `fetch on wrapped nested EngineObjectData also goes through instrumentation`() =
            runBlocking {
                // Given
                val nestedEngineObjectData: EngineObjectData = mockk()
                val mockEngineObjectData: EngineObjectData = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(parameters = mockk())

                every { mockEngineObjectData.type } returns testGraphQLObjectType
                every { nestedEngineObjectData.type } returns testGraphQLObjectType
                coEvery { mockEngineObjectData.fetch("nestedField") } returns nestedEngineObjectData
                coEvery { nestedEngineObjectData.fetch("leafField") } returns "leafValue"

                val testClass = InstrumentedEngineObjectData(mockEngineObjectData, instrumentation, state)

                // When
                val nested = testClass.fetch("nestedField") as InstrumentedEngineObjectData
                val leafResult = nested.fetch("leafField")

                // Then
                assertEquals("leafValue", leafResult)
                assertEquals(2, instrumentation.fetchSelectionContexts.size)
                val selections = instrumentation.fetchSelectionContexts.map { it.parameters.selection }
                assertEquals(listOf("nestedField", "leafField"), selections)
            }
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

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.get(selection) } returns expectedResult

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            // When
            val result = testClass.get(selection)

            // Then
            assertEquals(expectedResult, result)
            assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
            val context = instrumentation.syncFetchSelectionContexts.first()
            assertEquals(selection, context.parameters.selection)
            assertEquals("TestType", context.parameters.parentTypeName)
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

            every { mockEngineObjectData.type } returns testGraphQLObjectType
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
            val instrumentation = ThrowingResolverInstrumentation(throwOnInstrumentReadSelection = true)
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            every { mockEngineObjectData.type } returns testGraphQLObjectType
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

            every { mockEngineObjectData.type } returns testGraphQLObjectType
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

                every { mockEngineObjectData.type } returns testGraphQLObjectType
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

                every { mockEngineObjectData.type } returns testGraphQLObjectType
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
