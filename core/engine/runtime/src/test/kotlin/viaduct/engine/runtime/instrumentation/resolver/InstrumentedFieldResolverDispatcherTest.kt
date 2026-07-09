@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.instrumentation.resolver

import graphql.execution.ExecutionStepInfo
import graphql.execution.ResultPath
import graphql.schema.DataFetchingEnvironment
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.runtime.EngineExecutionContextExtensions.copy
import viaduct.engine.runtime.EngineExecutionContextExtensions.dataFetchingEnvironment
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.EngineObjectDataFactory
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.mocks.ContextMocks

@OptIn(ExperimentalCoroutinesApi::class)
internal class InstrumentedFieldResolverDispatcherTest {
    private val stubObjectFactory: EngineObjectDataFactory = EngineObjectDataFactory { mockk() }
    private val stubQueryFactory: EngineObjectDataFactory = EngineObjectDataFactory { mockk() }

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
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            val result = testClass.resolve(
                emptyMap(),
                stubObjectFactory,
                stubQueryFactory,
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
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation, coordinate)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, defaultContext)

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
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, defaultContext)

            // Then
            val executeContext = instrumentation.executeResolverContexts.first()
            assertNull(executeContext.parameters.fieldCoordinate)
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
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } throws exception

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When / Then
            val thrown = assertThrows<RuntimeException> {
                testClass.resolve(
                    emptyMap(),
                    stubObjectFactory,
                    stubQueryFactory,
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
            assertSame(thrown, executeContext.error)
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
                    stubObjectFactory,
                    stubQueryFactory,
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
                mockDispatcher.resolve(any(), any(), any(), any(), capture(capturedContext))
            } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(
                emptyMap(),
                stubObjectFactory,
                stubQueryFactory,
                null,
                defaultContext
            )

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
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val capturedContext = slot<EngineExecutionContext>()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery {
                mockDispatcher.resolve(any(), any(), any(), any(), capture(capturedContext))
            } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, defaultContext)

            // Then — the context passed to the inner dispatcher is wrapped in InstrumentedEngineExecutionContext
            capturedContext.captured.shouldBeInstanceOf<InstrumentedEngineExecutionContext>()
        }

    @Test
    fun `resolve wraps context in InstrumentedEngineExecutionContext even for DEFAULT instrumentation`() =
        runBlocking {
            // Given — wrapping is unconditional, so even DEFAULT instrumentation gets a wrapped context
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val capturedContext = slot<EngineExecutionContext>()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery {
                mockDispatcher.resolve(any(), any(), any(), any(), capture(capturedContext))
            } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, ViaductResolverInstrumentation.DEFAULT)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, defaultContext)

            // Then
            capturedContext.captured.shouldBeInstanceOf<InstrumentedEngineExecutionContext>()
        }

    @Test
    fun `resolve passes executionPath from dataFetchingEnvironment to instrumentation parameters`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val mockResolverMetadata = ResolverMetadata.forMock("mock-resolver")
            val expectedPath = ResultPath.parse("/Query/user")

            every { mockDispatcher.resolverMetadata } returns mockResolverMetadata
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            val mockExecutionStepInfo = mockk<ExecutionStepInfo>()
            every { mockExecutionStepInfo.path } returns expectedPath
            val mockDfe = mockk<DataFetchingEnvironment>()
            every { mockDfe.executionStepInfo } returns mockExecutionStepInfo

            val contextWithDfe = defaultContext.copy(dataFetchingEnvironment = mockDfe)
            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, contextWithDfe)

            // Then
            val executeContext = instrumentation.executeResolverContexts.first()
            assertEquals(expectedPath, executeContext.parameters.executionPath)
        }

    @Test
    fun `resolve passes null executionPath when dataFetchingEnvironment is not set`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery { mockDispatcher.resolve(any(), any(), any(), any(), any()) } returns "result"

            // defaultContext has no dataFetchingEnvironment set
            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, defaultContext)

            // Then
            val executeContext = instrumentation.executeResolverContexts.first()
            assertNull(executeContext.parameters.executionPath)
        }

    @Test
    fun `sync getter is wrapped with InstrumentedEngineObjectData_Sync`() =
        runBlocking {
            // Given
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val instrumentation = RecordingResolverInstrumentation()
            val capturedObjectFactory = slot<EngineObjectDataFactory>()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery {
                mockDispatcher.resolve(any(), capture(capturedObjectFactory), any(), any(), any())
            } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, instrumentation)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, defaultContext)

            // Then — sync getter returns InstrumentedEngineObjectData.Sync
            capturedObjectFactory.captured.create(null).shouldBeInstanceOf<InstrumentedEngineObjectData.Sync>()
        }

    @Test
    fun `sync getter is wrapped with InstrumentedEngineObjectData_Sync even for DEFAULT instrumentation`() =
        runBlocking {
            // Given — DEFAULT instrumentation
            val mockDispatcher: FieldResolverDispatcher = mockk()
            val capturedObjectFactory = slot<EngineObjectDataFactory>()

            every { mockDispatcher.resolverMetadata } returns ResolverMetadata.forMock("mock-resolver")
            coEvery {
                mockDispatcher.resolve(any(), capture(capturedObjectFactory), any(), any(), any())
            } returns "result"

            val testClass = InstrumentedFieldResolverDispatcher(mockDispatcher, ViaductResolverInstrumentation.DEFAULT)

            // When
            testClass.resolve(emptyMap(), stubObjectFactory, stubQueryFactory, null, defaultContext)

            // Then — sync getter returns InstrumentedEngineObjectData.Sync
            capturedObjectFactory.captured.create(null).shouldBeInstanceOf<InstrumentedEngineObjectData.Sync>()
        }
}
