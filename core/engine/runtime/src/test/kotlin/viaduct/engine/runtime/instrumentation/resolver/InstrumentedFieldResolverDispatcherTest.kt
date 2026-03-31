@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.mocks.ContextMocks

@OptIn(ExperimentalCoroutinesApi::class)
internal class InstrumentedFieldResolverDispatcherTest {
    private val stubSyncObjectValue: suspend () -> EngineObjectData.Sync = { mockk() }
    private val stubSyncQueryValue: suspend () -> EngineObjectData.Sync = { mockk() }

    private val defaultContext: EngineExecutionContextImpl
        get() = ContextMocks().engineExecutionContextImpl

    @Test
    fun `resolve calls instrumentation during execution`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata = ResolverMetadata.forMock("mock-resolver")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve(
                emptyMap(),
                mockk(),
                mockk(),
                stubSyncObjectValue,
                stubSyncQueryValue,
                null,
                defaultContext
            )

            // Then
            assertEquals("result", result)
            assertEquals(1, instrumentation.executeResolverContexts.size)
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(mockResolverMetadata, executeContext.parameters.resolverMetadata)
            assertEquals("result", executeContext.result)
            assertNull(executeContext.error)
        }

    @Test
    fun `resolve passes fieldCoordinate to instrumentation parameters`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata = ResolverMetadata.forMock("mock-resolver")
            val coordinate = "User" to "name"

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation, coordinate)

            // When
            testClass.resolve(emptyMap(), mockk(), mockk(), stubSyncObjectValue, stubSyncQueryValue, null, defaultContext)

            // Then
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(coordinate, executeContext.parameters.fieldCoordinate)
        }

    @Test
    fun `resolve passes null fieldCoordinate when no coordinate is provided`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(emptyMap(), mockk(), mockk(), stubSyncObjectValue, stubSyncQueryValue, null, defaultContext)

            // Then
            val executeContext = instrumentation.executeResolverContexts.first()
            assertNull(executeContext.parameters.fieldCoordinate)
        }

    @Test
    fun `resolve passes syncValueComputation to instrumentation parameters`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation, syncValueComputation = true)

            // When
            testClass.resolve(emptyMap(), mockk(), mockk(), stubSyncObjectValue, stubSyncQueryValue, null, defaultContext)

            // Then
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(true, executeContext.parameters.syncValueComputation)
        }

    @Test
    fun `resolve defaults syncValueComputation to false`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(emptyMap(), mockk(), mockk(), stubSyncObjectValue, stubSyncQueryValue, null, defaultContext)

            // Then
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(false, executeContext.parameters.syncValueComputation)
        }

    @Test
    fun `resolve calls instrumentation with error on exception`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata = ResolverMetadata.forMock("mock-resolver")
            val exception = RuntimeException("test error")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any(), any(), any()) } throws exception

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.resolve(
                    emptyMap(),
                    mockk(),
                    mockk(),
                    stubSyncObjectValue,
                    stubSyncQueryValue,
                    null,
                    defaultContext
                )
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
    fun `resolve passes raw object and query values when shouldInstrumentFetchSelections is false`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val rawObjectValue: EngineObjectData = mockk()
            val rawQueryValue: EngineObjectData = mockk()
            val capturedObjectValue = slot<EngineObjectData>()
            val capturedQueryValue = slot<EngineObjectData>()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery {
                mockDispatcher.resolve(any(), capture(capturedObjectValue), capture(capturedQueryValue), any(), any(), any(), any())
            } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, ViaductResolverInstrumentation.DEFAULT)

            // When
            testClass.resolve(emptyMap(), rawObjectValue, rawQueryValue, stubSyncObjectValue, stubSyncQueryValue, null, defaultContext)

            // Then — raw values passed through, not wrapped in InstrumentedEngineObjectData
            assertSame(rawObjectValue, capturedObjectValue.captured)
            assertSame(rawQueryValue, capturedQueryValue.captured)
            assertFalse(capturedObjectValue.captured is InstrumentedEngineObjectData)
            assertFalse(capturedQueryValue.captured is InstrumentedEngineObjectData)
        }

    @Test
    fun `resolve propagates instrumentation exceptions during execution`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = ThrowingResolverInstrumentation(throwOnInstrumentExecute = true)
            val mockResolverMetadata: ResolverMetadata = ResolverMetadata.forMock("mock-resolver")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // Make sure the exception is propogated to the top level when the instrumentation decides to throw
            assertThrows<RuntimeException> {
                testClass.resolve(
                    emptyMap(),
                    mockk(),
                    mockk(),
                    stubSyncObjectValue,
                    stubSyncQueryValue,
                    null,
                    defaultContext
                )
            }
        }

    @Test
    fun `resolve stamps resolver attribution on context passed to dispatcher`() =
        runBlocking {
            // Given
            val resolverName = "my-field-resolver"
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata = ResolverMetadata.forMock(resolverName)
            val capturedContext = slot<EngineExecutionContext>()

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery {
                mockDispatcher.resolve(any(), any(), any(), any(), any(), any(), capture(capturedContext))
            } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(
                emptyMap(),
                mockk(),
                mockk(),
                stubSyncObjectValue,
                stubSyncQueryValue,
                null,
                defaultContext
            )

            // Then - the context passed to the underlying dispatcher must carry the resolver's attribution
            assertEquals(
                ExecutionAttribution.fromResolver(resolverName),
                capturedContext.captured.fieldScope.attribution
            )
        }
}
