@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import graphql.schema.GraphQLObjectType
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
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

    @Nested
    inner class SyncTests {
        @Test
        fun `get calls instrumentation during execution`() {
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

            val result = testClass.get(selection)

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
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )
            val selection = "testField"

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.getOrNull(selection) } returns null

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            val resultOrNull = testClass.getOrNull(selection)

            assertNull(resultOrNull)
            assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
            val context = instrumentation.syncFetchSelectionContexts.first()
            assertEquals(selection, context.parameters.selection)
            assertNull(context.result)
            assertNull(context.error)
        }

        @Test
        fun `get propagates instrumentation exceptions`() {
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnInstrumentReadSelection = true)
            val state = instrumentation.createInstrumentationState(
                parameters = mockk()
            )

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            assertThrows<RuntimeException> {
                testClass.get("testField")
            }
        }

        @Test
        fun `get propagates get exceptions`() {
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(mockk())
            val selection = "testField"
            val getException = RuntimeException("Get failed")

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.get(selection) } throws getException

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            val thrown = assertThrows<RuntimeException> {
                testClass.get(selection)
            }

            assertSame(getException, thrown)
            assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
            val context = instrumentation.syncFetchSelectionContexts.first()
            assertNull(context.result)
            assertSame(getException, context.error)
        }

        @Test
        fun `get wraps returned sync EngineObjectData with instrumentation`() {
            val nestedEngineObjectData: EngineObjectData.Sync = mockk()
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(parameters = mockk())

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { nestedEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.get("nestedField") } returns nestedEngineObjectData

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            val result = testClass.get("nestedField")

            result.shouldBeInstanceOf<InstrumentedEngineObjectData.Sync>()
            assertSame(nestedEngineObjectData, result.engineObjectData)
        }

        @Test
        fun `get wraps sync EngineObjectData values inside a list`() {
            val nestedEngineObjectData: EngineObjectData.Sync = mockk()
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(parameters = mockk())

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { nestedEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.get("listField") } returns listOf(nestedEngineObjectData)

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            val result = testClass.get("listField") as List<*>

            assertEquals(1, result.size)
            result[0].shouldBeInstanceOf<InstrumentedEngineObjectData.Sync>()
            assertSame(nestedEngineObjectData, (result[0] as InstrumentedEngineObjectData.Sync).engineObjectData)
        }

        @Test
        fun `get returns original list unchanged when no elements need wrapping`() {
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(parameters = mockk())
            val scalarList = listOf("a", "b", "c")

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.get("scalarListField") } returns scalarList

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            val result = testClass.get("scalarListField")

            assertSame(scalarList, result)
        }

        @Test
        fun `get does not double-wrap an already instrumented sync EngineObjectData`() {
            val innerEngineObjectData: EngineObjectData.Sync = mockk()
            val alreadyInstrumented = InstrumentedEngineObjectData.Sync(innerEngineObjectData, RecordingResolverInstrumentation(), mockk())
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(parameters = mockk())

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { innerEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.get("field") } returns alreadyInstrumented

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            val result = testClass.get("field")

            assertSame(alreadyInstrumented, result)
        }

        @Test
        fun `get on wrapped nested sync EngineObjectData also goes through instrumentation`() {
            val nestedEngineObjectData: EngineObjectData.Sync = mockk()
            val mockEngineObjectData: EngineObjectData.Sync = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val state = instrumentation.createInstrumentationState(parameters = mockk())

            every { mockEngineObjectData.type } returns testGraphQLObjectType
            every { nestedEngineObjectData.type } returns testGraphQLObjectType
            every { mockEngineObjectData.get("nestedField") } returns nestedEngineObjectData
            every { nestedEngineObjectData.get("leafField") } returns "leafValue"

            val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

            val nested = testClass.get("nestedField") as InstrumentedEngineObjectData.Sync
            val leafResult = nested.get("leafField")

            assertEquals("leafValue", leafResult)
            assertEquals(2, instrumentation.syncFetchSelectionContexts.size)
            val selections = instrumentation.syncFetchSelectionContexts.map { it.parameters.selection }
            assertEquals(listOf("nestedField", "leafField"), selections)
        }

        @Test
        fun `fetch delegates to get with instrumentation`() =
            runBlocking {
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

                val result = testClass.fetch(selection)

                assertEquals(expectedResult, result)
                assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
                val context = instrumentation.syncFetchSelectionContexts.first()
                assertEquals(selection, context.parameters.selection)
                assertEquals(expectedResult, context.result)
            }

        @Test
        fun `fetchOrNull delegates to getOrNull with instrumentation`() =
            runBlocking {
                val mockEngineObjectData: EngineObjectData.Sync = mockk()
                val instrumentation = RecordingResolverInstrumentation()
                val state = instrumentation.createInstrumentationState(
                    parameters = mockk()
                )
                val selection = "testField"

                every { mockEngineObjectData.type } returns testGraphQLObjectType
                every { mockEngineObjectData.getOrNull(selection) } returns null

                val testClass = InstrumentedEngineObjectData.Sync(mockEngineObjectData, instrumentation, state)

                val resultOrNull = testClass.fetchOrNull(selection)

                assertNull(resultOrNull)
                assertEquals(1, instrumentation.syncFetchSelectionContexts.size)
                val context = instrumentation.syncFetchSelectionContexts.first()
                assertEquals(selection, context.parameters.selection)
                assertNull(context.result)
            }
    }
}
